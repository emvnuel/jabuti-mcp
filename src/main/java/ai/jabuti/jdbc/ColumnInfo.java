package ai.jabuti.jdbc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One column, keyed exactly as the dbhub contract expects
 * ({@code column_name/data_type/is_nullable/column_default}).
 */
public record ColumnInfo(String column_name, String data_type, String is_nullable, String column_default) {

    /**
     * The column's wire shape ({@code type/nullable/default}).
     */
    public Map<String, Object> details() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", data_type);
        details.put("nullable", "YES".equals(is_nullable));
        details.put("default", column_default);
        return details;
    }

    /**
     * The column's search wire shape ({@code name} plus {@link #details()}).
     */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", column_name);
        payload.putAll(details());
        return payload;
    }

    /**
     * The column's light wire shape for table summaries ({@code name} and
     * {@code type} only), keeping summary payloads small; {@link #toPayload()}
     * carries the full detail.
     */
    public Map<String, Object> toSummaryPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", column_name);
        payload.put("type", data_type);
        return payload;
    }
}
