package ai.jabuti.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.jabuti.datasource.DatasourceManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The opt-in {@code health_check} tool: pool state, a {@code SELECT 1} probe
 * and the database product/version. Generic MVP — engine-specific metrics
 * degrade to {@code notes} instead of failing (mirrors dbhub's {@code notes}
 * pattern). All JDBC reads are owned by {@link DatasourceManager}. Returns
 * envelope data or throws {@link ToolError}.
 */
@Singleton
public class HealthCheckHandler {

    private static final String MVP_NOTE = "engine-specific metrics not implemented in MVP";

    private final DatasourceManager datasourceManager;

    @Inject
    public HealthCheckHandler(DatasourceManager datasourceManager) {
        this.datasourceManager = datasourceManager;
    }

    public Map<String, Object> check(String sourceId) {
        datasourceManager.ensureConnected(sourceId);

        DatasourceManager.DatabaseInfo databaseInfo = datasourceManager.databaseInfo(sourceId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source_id", sourceId);
        data.put("connections", datasourceManager.poolMetrics(sourceId).toPayload());
        data.put("database", databaseSection(databaseInfo));
        data.put("probe", Map.of("select1_ok", databaseInfo.selectOneOk()));
        data.put("bufferCache", null);
        data.put("notes", mergedNotes(databaseInfo));
        return data;
    }

    private static Map<String, Object> databaseSection(DatasourceManager.DatabaseInfo databaseInfo) {
        Map<String, Object> database = new LinkedHashMap<>();
        database.put("product", databaseInfo.product());
        database.put("version", databaseInfo.version());
        return database;
    }

    private static List<String> mergedNotes(DatasourceManager.DatabaseInfo databaseInfo) {
        List<String> notes = new ArrayList<>();
        notes.add(MVP_NOTE);
        notes.addAll(databaseInfo.notes());
        return notes;
    }
}
