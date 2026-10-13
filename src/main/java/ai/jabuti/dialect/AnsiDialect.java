package ai.jabuti.dialect;

/**
 * The portable fallback for engines without a built-in dialect: its behavior
 * is entirely the ANSI defaults of {@link Dialect}. The id keeps the historic
 * {@code OTHER} value reported by {@code database_info}.
 */
final class AnsiDialect extends Dialect {

    AnsiDialect() {
        super("OTHER");
    }
}
