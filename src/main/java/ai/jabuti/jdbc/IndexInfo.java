package ai.jabuti.jdbc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One index, keyed exactly as the dbhub contract expects
 * ({@code index_name/column_names/is_unique/is_primary}).
 */
public record IndexInfo(String index_name, List<String> column_names, boolean is_unique, boolean is_primary) {

    /**
     * The index's wire shape ({@code columns/unique/primary}).
     */
    public Map<String, Object> details() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("columns", column_names);
        details.put("unique", is_unique);
        details.put("primary", is_primary);
        return details;
    }

    /**
     * The index's search wire shape ({@code name} plus {@link #details()}).
     */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", index_name);
        payload.putAll(details());
        return payload;
    }
}
