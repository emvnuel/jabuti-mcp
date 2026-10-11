package ai.jabuti.tools;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One {@code search_objects} capability: produces the candidate items of a
 * single object type within one schema. The shared search loop
 * ({@link ObjectSearch}) owns pattern filtering, the limit gate and detail
 * gating; a scanner owns which metadata reads produce its items and how
 * their details degrade.
 */
public interface ObjectSearcher {

    /**
     * The wire name of the object type this scanner serves
     * ({@code schema|table|view|column|index}).
     */
    String objectType();

    /**
     * The candidate items of one schema, lazily consumed so a reached limit
     * stops further metadata reads. Reads that an engine or schema does not
     * support degrade to "no items" (see {@link QuietMetadata}).
     */
    Iterable<ObjectItem> items(String sourceId, SearchRequest request, String schema);

    /**
     * One searchable item: its name (matched against the pattern), the
     * context keys identifying where it was found ({@code schema}, and
     * {@code table} for table-scoped types), and its details on demand.
     */
    record ObjectItem(String name, Map<String, Object> context, Supplier<Map<String, Object>> details) {
    }
}
