package ai.jabuti.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * A Maven repository as declared in YAML. The {@code url} is the full
 * repository base — nothing is appended to it. Credentials are optional and
 * sent as HTTP basic auth. A blank {@code id} defaults to
 * {@link #DEFAULT_ID}, which keeps caches/downloads compatible with the
 * implicit Maven Central repository used when no {@code repositories:}
 * section is configured.
 */
public record RepositoryEntry(String id, String url, String username, String password) {

    public static final String DEFAULT_ID = "central";

    private static final Set<String> SUPPORTED_SCHEMES = Set.of("http", "https", "file");

    public RepositoryEntry {
        if (id == null || id.isBlank()) {
            id = DEFAULT_ID;
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("repository '" + id + "' is missing 'url'");
        }
        if (password != null && (username == null || username.isBlank())) {
            throw new IllegalArgumentException("repository '" + id + "' defines 'password' without 'username'");
        }
        validateUrl(url, id);
    }

    /**
     * Whether the entry carries a username; an empty password is allowed.
     */
    public boolean hasCredentials() {
        return username != null && !username.isBlank();
    }

    private static void validateUrl(String url, String id) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            // e.getMessage() would embed the raw URL; only the reason and
            // position are safe, since the URL may carry credentials.
            throw new IllegalArgumentException("repository '" + id + "' has an invalid 'url' (" + e.getReason()
                    + " at index " + e.getIndex() + "); expected an absolute http(s):// or file:// URL"
                    + (url.contains("${") ? " (an unresolved ${VAR} may be the cause)" : ""));
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw new IllegalArgumentException("repository '" + id + "' has a relative 'url'"
                    + "; expected an absolute http(s):// or file:// URL");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_SCHEMES.contains(scheme)) {
            // Only the scheme is echoed: the raw URL may carry credentials.
            throw new IllegalArgumentException("repository '" + id + "' has an unsupported 'url' scheme '" + scheme
                    + "'; expected http(s):// or file://");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("repository '" + id
                    + "' puts credentials in 'url'; use the 'username' and 'password' keys instead");
        }
        if (!"file".equals(scheme)) {
            try {
                // Rejects authorities the HTTP transport cannot use, e.g. a
                // password with an unencoded '@' that getUserInfo() misses.
                uri.parseServerAuthority();
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("repository '" + id + "' has an invalid 'url' (not a valid host):"
                        + " if credentials are embedded in the URL, use the 'username' and 'password' keys instead");
            }
        }
    }
}
