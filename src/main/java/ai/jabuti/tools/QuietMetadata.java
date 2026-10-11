package ai.jabuti.tools;

import java.util.List;
import java.util.function.Supplier;

import ai.jabuti.datasource.DatasourceManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Reads metadata defensively: an unsupported engine or schema read is "no
 * objects there" instead of a failed search. A dead connection is not such a
 * failure: the source is probed again and the search fails with
 * {@code SEARCH_ERROR} if it no longer answers.
 */
@Singleton
class QuietMetadata {

    private final DatasourceManager datasourceManager;

    @Inject
    QuietMetadata(DatasourceManager datasourceManager) {
        this.datasourceManager = datasourceManager;
    }

    <T> List<T> list(String sourceId, Supplier<List<T>> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            datasourceManager.ensureConnected(sourceId);
            return List.of();
        }
    }
}
