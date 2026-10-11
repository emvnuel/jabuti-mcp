package ai.jabuti.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.jabuti.datasource.DatasourceManager;
import ai.jabuti.dialect.DbFamily;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * 100% generic schema discovery over {@link DatabaseMetaData} (the simplified
 * DBeaver {@code GenericMetaModel}): schemas, tables, views, columns and
 * indexes work on any JDBC database. Object lookups first try the name as a
 * schema and fall back to treating it as a catalog, which covers MySQL/MariaDB
 * where "schemas" are really catalogs.
 */
@Singleton
public class MetadataService {

    private final DatasourceManager datasourceManager;

    @Inject
    public MetadataService(DatasourceManager datasourceManager) {
        this.datasourceManager = datasourceManager;
    }

    public List<String> getSchemas(String sourceId) {
        return datasourceManager.withConnection(sourceId, connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            List<String> schemas = collect(metaData.getSchemas(), "TABLE_SCHEM");
            if (schemas.isEmpty()) {
                schemas = collect(metaData.getCatalogs(), "TABLE_CAT");
            }
            return schemas.stream().filter(name -> name != null && !name.isBlank()).distinct().sorted().toList();
        });
    }

    /**
     * Scopes searches to the connected database when the caller passes no
     * explicit schema. The scope rule lives on the dialect (see
     * {@link DbFamily#defaultSchemaScope}); the current catalog is only read
     * for catalog-based engines.
     */
    public List<String> defaultSchemas(String sourceId) {
        return datasourceManager.family(sourceId).defaultSchemaScope(getSchemas(sourceId), () -> getCatalog(sourceId));
    }

    public List<String> getTables(String sourceId, String schema) {
        return objects(sourceId, schema, "TABLE");
    }

    public List<String> getViews(String sourceId, String schema) {
        return objects(sourceId, schema, "VIEW");
    }

    /**
     * The connection's current catalog (the connected database on
     * catalog-based engines such as MySQL/MariaDB).
     */
    public String getCatalog(String sourceId) {
        return datasourceManager.withConnection(sourceId, Connection::getCatalog);
    }

    public List<ColumnInfo> getColumns(String sourceId, String schema, String table) {
        return datasourceManager.withConnection(sourceId, connection -> withCatalogFallback(schema,
                (catalog, effectiveSchema) -> readColumns(connection.getMetaData(), catalog, effectiveSchema, table)));
    }

    public List<IndexInfo> getIndexes(String sourceId, String schema, String table) {
        return datasourceManager.withConnection(sourceId, connection -> withCatalogFallback(schema,
                (catalog, effectiveSchema) -> readIndexes(connection.getMetaData(), catalog, effectiveSchema, table)));
    }

    private List<String> objects(String sourceId, String schema, String type) {
        return datasourceManager.withConnection(sourceId, connection -> withCatalogFallback(schema,
                (catalog, effectiveSchema) -> readObjectNames(connection.getMetaData(), catalog, effectiveSchema, type)));
    }

    /**
     * Reads with the name as a JDBC schema pattern and, when that finds
     * nothing, retries with it as a catalog pattern.
     */
    private static <T> List<T> withCatalogFallback(String schema, CatalogRead<T> read) throws SQLException {
        List<T> results = read.apply(null, schema);
        if (results.isEmpty() && schema != null) {
            results = read.apply(schema, null);
        }
        return results;
    }

    private static List<String> readObjectNames(DatabaseMetaData metaData, String catalog, String schema, String type)
            throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet resultSet = metaData.getTables(catalog, schema, "%", new String[] { type })) {
            while (resultSet.next()) {
                String name = resultSet.getString("TABLE_NAME");
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static List<ColumnInfo> readColumns(DatabaseMetaData metaData, String catalog, String schema, String table)
            throws SQLException {
        record OrderedColumn(int position, ColumnInfo info) {
        }
        List<OrderedColumn> columns = new ArrayList<>();
        try (ResultSet resultSet = metaData.getColumns(catalog, schema, table, "%")) {
            while (resultSet.next()) {
                columns.add(new OrderedColumn(resultSet.getInt("ORDINAL_POSITION"), new ColumnInfo(
                        resultSet.getString("COLUMN_NAME"),
                        resultSet.getString("TYPE_NAME"),
                        resultSet.getString("IS_NULLABLE"),
                        resultSet.getString("COLUMN_DEF"))));
            }
        }
        return columns.stream()
                .sorted(Comparator.comparingInt(OrderedColumn::position))
                .map(OrderedColumn::info)
                .toList();
    }

    private static List<IndexInfo> readIndexes(DatabaseMetaData metaData, String catalog, String schema, String table)
            throws SQLException {
        Map<String, List<IndexAssembler.IndexColumn>> columnsByIndex = new LinkedHashMap<>();
        Map<String, Boolean> uniqueByIndex = new LinkedHashMap<>();
        try (ResultSet resultSet = metaData.getIndexInfo(catalog, schema, table, false, false)) {
            while (resultSet.next()) {
                String indexName = resultSet.getString("INDEX_NAME");
                if (indexName == null) {
                    continue;
                }
                columnsByIndex.computeIfAbsent(indexName, key -> new ArrayList<>())
                        .add(new IndexAssembler.IndexColumn(resultSet.getInt("ORDINAL_POSITION"),
                                resultSet.getString("COLUMN_NAME")));
                uniqueByIndex.putIfAbsent(indexName, !resultSet.getBoolean("NON_UNIQUE"));
            }
        }
        return IndexAssembler.assemble(columnsByIndex, uniqueByIndex, readPrimaryKey(metaData, catalog, schema, table));
    }

    private static IndexAssembler.PrimaryKey readPrimaryKey(DatabaseMetaData metaData, String catalog, String schema,
            String table) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        Set<String> columns = new LinkedHashSet<>();
        try (ResultSet resultSet = metaData.getPrimaryKeys(catalog, schema, table)) {
            while (resultSet.next()) {
                String pkName = resultSet.getString("PK_NAME");
                if (pkName != null) {
                    names.add(pkName);
                }
                String columnName = resultSet.getString("COLUMN_NAME");
                if (columnName != null) {
                    columns.add(columnName);
                }
            }
        }
        return new IndexAssembler.PrimaryKey(names, columns);
    }

    private static List<String> collect(ResultSet resultSet, String column) throws SQLException {
        List<String> values = new ArrayList<>();
        try (resultSet) {
            while (resultSet.next()) {
                values.add(resultSet.getString(column));
            }
        }
        return values;
    }

    @FunctionalInterface
    private interface CatalogRead<T> {
        List<T> apply(String catalog, String schema) throws SQLException;
    }
}
