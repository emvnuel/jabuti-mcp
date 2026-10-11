package ai.jabuti.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import ai.jabuti.jdbc.MetadataService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The shared search loop behind every object type: iterate the requested (or
 * default) schemas, filter the scanner's candidate items by the LIKE
 * pattern, stop at the limit and shape one wire result per item — with
 * details only at non-{@code names} levels.
 */
@Singleton
public class ObjectSearch {

    private final MetadataService metadataService;

    @Inject
    public ObjectSearch(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    List<Map<String, Object>> search(String sourceId, SearchRequest request, ObjectSearcher scanner) {
        Pattern regex = SearchPatterns.likePatternToRegex(request.pattern());
        Results results = new Results(request.limit());
        for (String schema : schemasToSearch(sourceId, request)) {
            if (results.atLimit()) {
                break;
            }
            for (ObjectSearcher.ObjectItem item : scanner.items(sourceId, request, schema)) {
                if (results.atLimit()) {
                    break;
                }
                if (!regex.matcher(item.name()).matches()) {
                    continue;
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("name", item.name());
                result.putAll(item.context());
                if (!"names".equals(request.detailLevel())) {
                    result.putAll(item.details().get());
                }
                results.add(result);
            }
        }
        return results.asList();
    }

    private List<String> schemasToSearch(String sourceId, SearchRequest request) {
        if (request.schema() != null) {
            return List.of(request.schema());
        }
        return metadataService.defaultSchemas(sourceId);
    }
}
