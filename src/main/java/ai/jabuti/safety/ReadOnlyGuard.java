package ai.jabuti.safety;

import ai.jabuti.jdbc.StatementBatch;
import ai.jabuti.jdbc.StatementClass;
import jakarta.inject.Singleton;

/**
 * The read-only policy over a parsed {@link StatementBatch}: in readonly
 * mode every statement must be {@link StatementClass#READ}. An empty batch
 * has nothing to classify and is denied, matching the {@code UNKNOWN}
 * verdict of a statement the parser cannot classify.
 */
@Singleton
public class ReadOnlyGuard {

    public boolean isAllowed(boolean readonly, StatementBatch batch) {
        if (!readonly) {
            return true;
        }
        return !batch.isEmpty() && batch.statements().stream()
                .allMatch(statement -> statement.statementClass() == StatementClass.READ);
    }
}
