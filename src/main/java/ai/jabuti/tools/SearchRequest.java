package ai.jabuti.tools;

import java.util.Set;

/**
 * One validated {@code search_objects} request: the external args are parsed
 * into effective filter values exactly once, and a request can only be
 * constructed in a valid state. Rejections surface as {@link ToolError} with
 * the envelope code the client receives.
 */
record SearchRequest(String objectType, String pattern, String schema, String table, String detailLevel, int limit) {

    private static final Set<String> OBJECT_TYPES = Set.of(
            "schema", "table", "view", "column", "procedure", "function", "index");
    private static final Set<String> DETAIL_LEVELS = Set.of("names", "summary", "full");
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    static SearchRequest parse(String objectType, String pattern, String schema, String table, String detailLevel,
            Integer limit) {
        String effectiveTable = blankToNull(table);
        String effectiveSchema = blankToNull(schema);
        validateObjectType(objectType);
        validateTableFilter(objectType, effectiveSchema, effectiveTable);
        return new SearchRequest(objectType, effectivePattern(pattern), effectiveSchema, effectiveTable,
                validatedDetailLevel(detailLevel), validatedLimit(limit));
    }

    private static void validateObjectType(String objectType) {
        if (objectType == null || !OBJECT_TYPES.contains(objectType)) {
            throw new ToolError("Unsupported object_type: " + objectType, ToolEnvelope.CODE_INVALID_OBJECT_TYPE);
        }
    }

    private static void validateTableFilter(String objectType, String schema, String table) {
        if (table == null) {
            return;
        }
        if (schema == null) {
            throw new ToolError("The 'table' parameter requires 'schema' to be specified",
                    ToolEnvelope.CODE_SCHEMA_REQUIRED);
        }
        if (!objectType.equals("column") && !objectType.equals("index")) {
            throw new ToolError("The 'table' parameter only applies to object_type 'column' or 'index', not '"
                    + objectType + "'", ToolEnvelope.CODE_INVALID_TABLE_FILTER);
        }
    }

    private static String effectivePattern(String pattern) {
        return pattern == null || pattern.isEmpty() ? "%" : pattern;
    }

    private static String validatedDetailLevel(String detailLevel) {
        String effective = blankToNull(detailLevel) == null ? "names" : detailLevel;
        if (!DETAIL_LEVELS.contains(effective)) {
            throw new ToolError("Invalid detail_level: " + detailLevel, ToolEnvelope.CODE_INVALID_INPUT);
        }
        return effective;
    }

    private static int validatedLimit(Integer limit) {
        int effective = limit == null ? DEFAULT_LIMIT : limit;
        if (effective <= 0 || effective > MAX_LIMIT) {
            throw new ToolError("'limit' must be between 1 and " + MAX_LIMIT, ToolEnvelope.CODE_INVALID_INPUT);
        }
        return effective;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
