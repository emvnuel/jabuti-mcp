package ai.jabuti.tools;

import java.util.ArrayList;
import java.util.List;

import ai.jabuti.jdbc.MetadataService;

/**
 * Common skeleton of the table-scoped scanners ({@code column},
 * {@code index}): iterate the candidate tables of a schema, with each
 * table's items produced lazily so a reached limit stops further reads.
 */
abstract class PerTableScanner implements ObjectSearcher {

    final MetadataService metadataService;
    private final QuietMetadata quiet;

    PerTableScanner(MetadataService metadataService, QuietMetadata quiet) {
        this.metadataService = metadataService;
        this.quiet = quiet;
    }

    @Override
    public final Iterable<ObjectItem> items(String sourceId, SearchRequest request, String schema) {
        return () -> candidateTables(sourceId, schema, request.table()).stream()
                .flatMap(table -> quietlyItems(sourceId, schema, table).stream())
                .iterator();
    }

    /**
     * The items of one table to search.
     */
    abstract List<ObjectItem> itemsOfTable(String sourceId, String schema, String table);

    /**
     * Whether candidate tables also include views ({@code column} does,
     * {@code index} does not: some engines reject index metadata for views).
     */
    boolean includeViews() {
        return false;
    }

    private List<ObjectItem> quietlyItems(String sourceId, String schema, String table) {
        return quiet.list(sourceId, () -> itemsOfTable(sourceId, schema, table));
    }

    private List<String> candidateTables(String sourceId, String schema, String tableFilter) {
        if (tableFilter != null) {
            return List.of(tableFilter);
        }
        if (!includeViews()) {
            return quiet.list(sourceId, () -> metadataService.getTables(sourceId, schema));
        }
        return quiet.list(sourceId, () -> tablesAndViews(sourceId, schema));
    }

    private List<String> tablesAndViews(String sourceId, String schema) {
        List<String> objects = new ArrayList<>(metadataService.getTables(sourceId, schema));
        try {
            objects.addAll(metadataService.getViews(sourceId, schema));
        } catch (RuntimeException e) {
            // Views may be unsupported for some connectors/schemas.
        }
        return objects;
    }
}
