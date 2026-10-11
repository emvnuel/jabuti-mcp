package ai.jabuti.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class SqlSplitterTest {

    @Test
    void splitsOnTopLevelSemicolons() {
        assertEquals(List.of("SELECT 1", "SELECT 2"), SqlSplitter.split("SELECT 1; SELECT 2"));
        assertEquals(List.of("SELECT 1", "SELECT 2"), SqlSplitter.split("SELECT 1;\nSELECT 2;\n"));
        assertEquals(List.of("SELECT 1"), SqlSplitter.split("SELECT 1;"));
        assertEquals(List.of(), SqlSplitter.split("  ;  "));
        assertEquals(List.of(), SqlSplitter.split(null));
    }

    @Test
    void keepsSemicolonsInsideQuotesAndComments() {
        assertEquals(List.of("SELECT 'a;b' AS x", "SELECT 2"), SqlSplitter.split("SELECT 'a;b' AS x; SELECT 2"));
        assertEquals(List.of("SELECT 'it''s; fine' AS x"), SqlSplitter.split("SELECT 'it''s; fine' AS x"));
        assertEquals(List.of("SELECT 1 -- ; not a separator", "SELECT 2"),
                SqlSplitter.split("SELECT 1 -- ; not a separator\n; SELECT 2"));
        assertEquals(List.of("SELECT 1 /* ; */", "SELECT 2"), SqlSplitter.split("SELECT 1 /* ; */ ; SELECT 2"));
        assertEquals(List.of("SELECT [a;b], `c;d`, \"e;f\" FROM t"),
                SqlSplitter.split("SELECT [a;b], `c;d`, \"e;f\" FROM t"));
    }

    @Test
    void backslashEscapesOnlyWhenRequested() {
        String sql = "SELECT 'a\\'b; c'";

        assertEquals(List.of("SELECT 'a\\'b", "c'"), SqlSplitter.split(sql));
        assertEquals(List.of("SELECT 'a\\'b; c'"), SqlSplitter.split(sql, true));
    }

    @Test
    void stripCommentsAndStringsBlanksNonCode() {
        assertEquals("SELECT   FROM t", SqlSplitter.stripCommentsAndStrings("SELECT 'a;b' FROM t"));
        assertEquals("SELECT", SqlSplitter.firstWord(
                SqlSplitter.stripCommentsAndStrings("/* c */ SELECT 1").trim()));
        assertEquals("", SqlSplitter.stripCommentsAndStrings(null));
    }

    @Test
    void stripHonorsBackslashEscapesWhenRequested() {
        String sql = "SELECT 'a\\'b'";

        assertEquals("SELECT  b ", SqlSplitter.stripCommentsAndStrings(sql));
        assertEquals("SELECT  ", SqlSplitter.stripCommentsAndStrings(sql, true));
    }
}
