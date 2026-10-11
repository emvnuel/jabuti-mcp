package ai.jabuti.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DsnRedactorTest {

    @Test
    void masksPasswordsInMessagesAndUrls() {
        assertEquals("auth failed for password *****", DsnRedactor.redact("auth failed for password s3cret", "s3cret"));
        assertEquals("jdbc:postgresql://user:*****@localhost:5432/app",
                DsnRedactor.redactUrl("jdbc:postgresql://user:s3cret@localhost:5432/app"));
    }

    @Test
    void masksOracleUserInfo() {
        assertEquals("jdbc:oracle:thin:scott/*****@localhost:1521:ORCL",
                DsnRedactor.redactUrl("jdbc:oracle:thin:scott/tiger@localhost:1521:ORCL"));
    }

    @Test
    void masksPasswordParameters() {
        assertEquals("jdbc:sqlserver://host:1433;databaseName=app;user=sa;password=*****;encrypt=true",
                DsnRedactor.redactUrl("jdbc:sqlserver://host:1433;databaseName=app;user=sa;password=P@ss;encrypt=true"));
        assertEquals("jdbc:mysql://host/db?user=root&password=*****",
                DsnRedactor.redactUrl("jdbc:mysql://host/db?user=root&password=s3cret"));
    }

    @Test
    void leavesPasswordlessUrlsUntouched() {
        assertEquals("jdbc:postgresql://localhost:5432/app",
                DsnRedactor.redactUrl("jdbc:postgresql://localhost:5432/app"));
    }
}
