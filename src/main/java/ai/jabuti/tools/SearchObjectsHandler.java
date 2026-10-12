package ai.jabuti.tools;

import java.util.ArrayList;
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
    private static final String SCHEMALESS_SCHEMA_IGNORED_NOTE = "database has no schema concept; 'schema' parameter ignored";
    private static final String SCHEMALESS_EMPTY_NOTE = "database has no schema concept; no schemas to list";

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
        List<String> notes = new ArrayList<>();
        SearchRequest effective = request;
        List<String> availableSchemas = null;
        if (request.schema() != null) {
            availableSchemas = metadataService.getSchemas(sourceId);
            switch (resolveSchema(availableSchemas, request.schema())) {
                case USE_DEFAULT_SCOPE -> {
                    effective = new SearchRequest(request.objectType(), request.pattern(), null, request.table(),
                            request.detailLevel(), request.limit());
                    notes.add(SCHEMALESS_SCHEMA_IGNORED_NOTE);
                }
                case USE_REQUESTED -> {
                    // The requested schema exists: keep it.
                }
                case SCHEMA_NOT_FOUND -> throw new ToolError(
                        "Schema '" + request.schema() + "' does not exist. Available schemas: "
                                + String.join(", ", availableSchemas),
                        ToolEnvelope.CODE_SCHEMA_NOT_FOUND,
                        "Pass one of the listed schemas, or omit 'schema' to search the default scope");
            }
        }

        ObjectSearcher searcher = searchers.get(effective.objectType());
        List<Map<String, Object>> results;
        if (searcher == null) {
            results = List.of();
            notes.addAll(ROUTINES_NOTE);
        } else {
            results = objectSearch.search(sourceId, effective, searcher);
            if ("schema".equals(effective.objectType())) {
                if (availableSchemas == null) {
                    availableSchemas = metadataService.getSchemas(sourceId);
                }
                if (availableSchemas.isEmpty()) {
                    notes.add(SCHEMALESS_EMPTY_NOTE);
                }
            }
        }
        return responseData(effective, results, notes);
    }

    /**
     * Decides how a requested schema resolves against the engine's reported
     * schemas. A pure function of its inputs so the schema-less branches stay
     * unit-testable without a schema-less database.
     */
    enum SchemaResolution {
        USE_DEFAULT_SCOPE,
        USE_REQUESTED,
        SCHEMA_NOT_FOUND
    }

    static SchemaResolution resolveSchema(List<String> availableSchemas, String requestedSchema) {
        if (availableSchemas.isEmpty()) {
            return SchemaResolution.USE_DEFAULT_SCOPE;
        }
        if (availableSchemas.contains(requestedSchema)) {
            return SchemaResolution.USE_REQUESTED;
        }
        return SchemaResolution.SCHEMA_NOT_FOUND;
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
