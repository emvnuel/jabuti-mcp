package ai.jabuti.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import ai.jabuti.datasource.DatasourceManager;
import ai.jabuti.jdbc.MetadataService;
import io.quarkus.arc.All;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The {@code search_objects} tool: unified search/list with progressive
 * disclosure ({@code names|summary|full}), ported from dbhub's
 * {@code createSearchDatabaseObjectsToolHandler}. Always read-only.
 *
 * <p>This handler validates the request, checks the schema scope and
 * assembles the response; the per-type work lives behind the injected
 * {@link ObjectSearcher} scanners, driven by the shared {@link ObjectSearch}
 * loop.
 *
 * <p>Known MVP limitation: {@code procedure}/{@code function} return an empty
 * list with a {@code notes} entry — generic JDBC has no portable routine API.
 */
@Singleton
public class SearchObjectsHandler {

    private static final List<String> ROUTINES_NOTE = List.of("routines not supported over generic JDBC in MVP");

    private final DatasourceManager datasourceManager;
    private final MetadataService metadataService;
    private final ObjectSearch objectSearch;
    private final Map<String, ObjectSearcher> searchers;

    @Inject
    public SearchObjectsHandler(DatasourceManager datasourceManager, MetadataService metadataService,
            ObjectSearch objectSearch, @All List<ObjectSearcher> scanners) {
        this.datasourceManager = datasourceManager;
        this.metadataService = metadataService;
        this.objectSearch = objectSearch;
        this.searchers = scanners.stream()
                .collect(Collectors.toUnmodifiableMap(ObjectSearcher::objectType, Function.identity()));
    }

    public Map<String, Object> search(String sourceId, String objectType, String pattern, String schema, String table,
            String detailLevel, Integer limit) {
        SearchRequest request = SearchRequest.parse(objectType, pattern, schema, table, detailLevel, limit);

        datasourceManager.ensureConnected(sourceId);
        String schemaError = schemaNotFoundError(sourceId, request);
        if (schemaError != null) {
            throw new ToolError(schemaError, ToolEnvelope.CODE_SCHEMA_NOT_FOUND);
        }

        ObjectSearcher searcher = searchers.get(request.objectType());
        List<String> notes = List.of();
        List<Map<String, Object>> results;
        if (searcher == null) {
            results = List.of();
            notes = ROUTINES_NOTE;
        } else {
            results = objectSearch.search(sourceId, request, searcher);
        }
        return responseData(request, results, notes);
    }

    private String schemaNotFoundError(String sourceId, SearchRequest request) {
        if (request.schema() == null) {
            return null;
        }
        List<String> schemas = metadataService.getSchemas(sourceId);
        if (schemas.contains(request.schema())) {
            return null;
        }
        return "Schema '" + request.schema() + "' does not exist. Available schemas: " + String.join(", ", schemas);
    }

    private static Map<String, Object> responseData(SearchRequest request, List<Map<String, Object>> results,
            List<String> notes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("object_type", request.objectType());
        data.put("pattern", request.pattern());
        data.put("schema", request.schema());
        data.put("table", request.table());
        data.put("detail_level", request.detailLevel());
        data.put("count", results.size());
        data.put("results", results);
        data.put("truncated", results.size() == request.limit());
        if (!notes.isEmpty()) {
            data.put("notes", notes);
        }
        return data;
    }
}
