package ai.jabuti.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import ai.jabuti.jdbc.ColumnInfo;
import ai.jabuti.jdbc.IndexInfo;
import ai.jabuti.jdbc.MetadataService;
import ai.jabuti.jdbc.QueryExecutor;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * {@code table} searches: names, or summary/full details with column counts,
 * row counts and index material.
 */
@Singleton
final class TableScanner implements ObjectSearcher {

    private final MetadataService metadataService;
    private final QuietMetadata quiet;
    private final QueryExecutor queryExecutor;

    @Inject
    TableScanner(MetadataService metadataService, QuietMetadata quiet, QueryExecutor queryExecutor) {
        this.metadataService = metadataService;
        this.quiet = quiet;
        this.queryExecutor = queryExecutor;
    }

    @Override
    public String objectType() {
        return "table";
    }

    @Override
    public Iterable<ObjectItem> items(String sourceId, SearchRequest request, String schema) {
        List<ObjectItem> items = new ArrayList<>();
        for (String table : quiet.list(sourceId, () -> metadataService.getTables(sourceId, schema))) {
            items.add(new ObjectItem(table, context(schema),
                    () -> details(sourceId, schema, table, request.detailLevel())));
        }
        return items;
    }

    private Map<String, Object> details(String sourceId, String schema, String table, String detailLevel) {
        if ("full".equals(detailLevel)) {
            return full(sourceId, schema, table);
        }
        return summary(sourceId, schema, table);
    }

    private Map<String, Object> summary(String sourceId, String schema, String table) {
        Map<String, Object> details = new LinkedHashMap<>();
        try {
            details.put("column_count", metadataService.getColumns(sourceId, schema, table).size());
            details.put("row_count", rowCount(sourceId, schema, table));
        } catch (RuntimeException e) {
            details.put("column_count", null);
            details.put("row_count", null);
        }
        return details;
    }

    private Map<String, Object> full(String sourceId, String schema, String table) {
        Map<String, Object> details = new LinkedHashMap<>();
        try {
            List<ColumnInfo> columns = metadataService.getColumns(sourceId, schema, table);
            List<IndexInfo> indexes = metadataService.getIndexes(sourceId, schema, table);
            details.put("column_count", columns.size());
            details.put("row_count", rowCount(sourceId, schema, table));
            details.put("columns", columns.stream().map(ColumnInfo::toPayload).toList());
            details.put("indexes", indexes.stream().map(IndexInfo::toPayload).toList());
        } catch (RuntimeException e) {
            details.put("error", "Unable to fetch full details: " + ToolEnvelope.message(e));
        }
        return details;
    }

    private Integer rowCount(String sourceId, String schema, String table) {
        try {
            OptionalInt count = queryExecutor.countRows(sourceId, schema, table);
            return count.isPresent() ? count.getAsInt() : null;
        } catch (RuntimeException e) {
            // Row count is best-effort metadata: degrade to null.
            return null;
        }
    }

    private static Map<String, Object> context(String schema) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("schema", schema);
        return context;
    }
}
