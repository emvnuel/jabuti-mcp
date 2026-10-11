package ai.jabuti.safety;

import java.util.regex.Pattern;

/**
 * Removes secrets from messages and URLs before they reach logs or tool
 * output. Passwords are only ever supplied via {@code ${ENV}} interpolation in
 * datasources.yaml and must never appear verbatim anywhere else.
 */
public final class DsnRedactor {

    public static final String MASK = "*****";

    private static final Pattern URL_USERINFO = Pattern.compile("(://[^/@:]+):[^/@]+@");
    private static final Pattern ORACLE_USERINFO =
            Pattern.compile("(jdbc:oracle:[a-zA-Z0-9]+:)([^/@:]+)/([^@]+)@", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_PASSWORD_PARAMETER =
            Pattern.compile("([;?&](?:password|pwd)=)[^;&]+", Pattern.CASE_INSENSITIVE);

    private DsnRedactor() {
    }

    /**
     * Replaces every occurrence of the given secrets in {@code message} with
     * {@value #MASK}.
     */
    public static String redact(String message, String... secrets) {
        if (message == null) {
            return null;
        }
        String result = message;
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                result = result.replace(secret, MASK);
            }
        }
        return result;
    }

    /**
     * Masks credentials embedded in a JDBC URL: the
     * {@code scheme://user:password@host/...} form, the Oracle thin
     * {@code user/password@host} form and {@code password=} / {@code pwd=}
     * URL or property parameters.
     */
    public static String redactUrl(String url) {
        if (url == null) {
            return null;
        }
        String masked = URL_PASSWORD_PARAMETER.matcher(url).replaceAll("$1" + MASK);
        masked = URL_USERINFO.matcher(masked).replaceAll("$1:" + MASK + "@");
        return ORACLE_USERINFO.matcher(masked).replaceAll("$1$2/" + MASK + "@");
    }
}
