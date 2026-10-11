package ai.jabuti.tools;

import java.util.regex.Pattern;

/**
 * Translates the tool's LIKE-style patterns ({@code %} any chars, {@code _}
 * one char) into case-insensitive anchored regexes.
 */
final class SearchPatterns {

    private SearchPatterns() {
    }

    static Pattern likePatternToRegex(String pattern) {
        String escaped = pattern
                .replaceAll("([.*+?^${}()|\\[\\]\\\\])", "\\\\$1")
                .replace("%", ".*")
                .replace("_", ".");
        return Pattern.compile("^" + escaped + "$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
