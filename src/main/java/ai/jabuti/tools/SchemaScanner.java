package ai.jabuti.tools;

import java.util.List;
import java.util.Map;

import ai.jabuti.jdbc.MetadataService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * {@code schema} searches: the search scope's schemas, with table counts at
 * non-{@code names} detail. A {@code null} schema stands for engines with no
 * schema concept and produces no item.
 */
@Singleton
final class SchemaScanner implements ObjectSearcher {

    private final MetadataService metadataService;

    @Inject
    SchemaScanner(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @Override
    public String objectType() {
        return "schema";
    }

    @Override
    public Iterable<ObjectItem> items(String sourceId, SearchRequest request, String schema) {
        if (schema == null) {
            return List.of();
        }
        return List.of(new ObjectItem(schema, Map.of(), () -> tableCountDetails(sourceId, schema)));
    }

    private Map<String, Object> tableCountDetails(String sourceId, String schema) {
        Integer tableCount = tableCount(sourceId, schema);
        return tableCount == null ? Map.of() : Map.of("table_count", tableCount);
    }

    private Integer tableCount(String sourceId, String schema) {
        try {
            return metadataService.getTables(sourceId, schema).size();
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
