package ai.jabuti.jdbc;

/**
 * The privilege class of one statement, ordered from least to most
 * privileged. A statement batch's verdict is the most privileged class
 * among its statements; the read-only gate admits only {@link #READ}.
 */
public enum StatementClass {
    READ, DML, DDL, ADMIN, UNKNOWN;
}
