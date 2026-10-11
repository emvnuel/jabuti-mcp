package ai.jabuti.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.jabuti.jdbc.MetadataService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * {@code index} searches: indexes of the candidate tables (or the single
 * table filter).
 */
@Singleton
final class IndexScanner extends PerTableScanner {

    @Inject
    IndexScanner(MetadataService metadataService, QuietMetadata quiet) {
        super(metadataService, quiet);
    }

    @Override
    public String objectType() {
        return "index";
    }

    @Override
    List<ObjectItem> itemsOfTable(String sourceId, String schema, String table) {
        return metadataService.getIndexes(sourceId, schema, table).stream()
                .map(index -> new ObjectItem(index.index_name(), context(table, schema), index::details))
                .toList();
    }

    private static Map<String, Object> context(String table, String schema) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("table", table);
        context.put("schema", schema);
        return context;
    }
}
