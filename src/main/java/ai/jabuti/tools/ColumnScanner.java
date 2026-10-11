package ai.jabuti.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.jabuti.jdbc.MetadataService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * {@code column} searches: columns of the candidate tables (tables and
 * views, or the single table filter).
 */
@Singleton
final class ColumnScanner extends PerTableScanner {

    @Inject
    ColumnScanner(MetadataService metadataService, QuietMetadata quiet) {
        super(metadataService, quiet);
    }

    @Override
    public String objectType() {
        return "column";
    }

    @Override
    boolean includeViews() {
        return true;
    }

    @Override
    List<ObjectItem> itemsOfTable(String sourceId, String schema, String table) {
        return metadataService.getColumns(sourceId, schema, table).stream()
                .map(column -> new ObjectItem(column.column_name(), context(table, schema), column::details))
                .toList();
    }

    private static Map<String, Object> context(String table, String schema) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("table", table);
        context.put("schema", schema);
        return context;
    }
}
