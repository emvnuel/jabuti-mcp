package ai.jabuti.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Accumulates search results up to a limit; callers check {@link #atLimit()}
 * before adding.
 */
final class Results {

    private final List<Map<String, Object>> items = new ArrayList<>();
    private final int limit;

    Results(int limit) {
        this.limit = limit;
    }

    boolean atLimit() {
        return items.size() >= limit;
    }

    void add(Map<String, Object> item) {
        items.add(item);
    }

    List<Map<String, Object>> asList() {
        return List.copyOf(items);
    }
}
