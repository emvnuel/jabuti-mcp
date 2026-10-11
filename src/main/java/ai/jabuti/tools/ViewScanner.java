package ai.jabuti.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.jabuti.jdbc.ColumnInfo;
import ai.jabuti.jdbc.MetadataService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * {@code view} searches: names, or summary/full details with column material.
 * Indexes are intentionally never queried for views.
 */
@Singleton
final class ViewScanner implements ObjectSearcher {

    private final MetadataService metadataService;
    private final QuietMetadata quiet;

    @Inject
    ViewScanner(MetadataService metadataService, QuietMetadata quiet) {
        this.metadataService = metadataService;
        this.quiet = quiet;
    }

    @Override
    public String objectType() {
        return "view";
    }

    @Override
    public Iterable<ObjectItem> items(String sourceId, SearchRequest request, String schema) {
        List<ObjectItem> items = new ArrayList<>();
        for (String view : quiet.list(sourceId, () -> metadataService.getViews(sourceId, schema))) {
            items.add(new ObjectItem(view, context(schema),
                    () -> details(sourceId, schema, view, request.detailLevel())));
        }
        return items;
    }

    private Map<String, Object> details(String sourceId, String schema, String view, String detailLevel) {
        Map<String, Object> details = new LinkedHashMap<>();
        try {
            List<ColumnInfo> columns = metadataService.getColumns(sourceId, schema, view);
            details.put("column_count", columns.size());
            if ("full".equals(detailLevel)) {
                details.put("columns", columns.stream().map(ColumnInfo::toPayload).toList());
                // Indexes are intentionally not queried for views: on some
                // engines getIndexInfo throws for a view, which would degrade
                // the whole result to an error.
                details.put("indexes", List.of());
            }
        } catch (RuntimeException e) {
            details.put("error", "Unable to fetch full details: " + ToolEnvelope.message(e));
        }
        return details;
    }

    private static Map<String, Object> context(String schema) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("schema", schema);
        return context;
    }
}
