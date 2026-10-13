package ai.jabuti.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import ai.jabuti.datasource.DatasourceManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The opt-in {@code database_info} tool: the engine's dialect hints for the
 * LLM (product/version, driver, identifier quoting, catalog/schema terms,
 * transaction support, SQL keywords). Read-only; the JDBC reads are owned by
 * {@link DatasourceManager}. Returns envelope data or throws {@link ToolError}.
 */
@Singleton
public class DatabaseInfoHandler {

    private final DatasourceManager datasourceManager;

    @Inject
    public DatabaseInfoHandler(DatasourceManager datasourceManager) {
        this.datasourceManager = datasourceManager;
    }

    public Map<String, Object> info(String sourceId) {
        datasourceManager.ensureConnected(sourceId);

        DatasourceManager.DialectInfo info = datasourceManager.dialectInfo(sourceId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source_id", sourceId);
        data.put("family", datasourceManager.dialect(sourceId).id());
        data.put("product", info.product());
        data.put("version", info.version());
        data.put("driver_name", info.driverName());
        data.put("driver_version", info.driverVersion());
        data.put("url", info.url());
        data.put("identifier_quote", info.identifierQuote());
        data.put("catalog_term", info.catalogTerm());
        data.put("schema_term", info.schemaTerm());
        data.put("read_only", info.readOnly());
        data.put("supports_transactions", info.supportsTransactions());
        data.put("max_connections", info.maxConnections());
        data.put("sql_keywords", info.sqlKeywords());
        if (!info.notes().isEmpty()) {
            data.put("notes", info.notes());
        }
        return data;
    }
}
