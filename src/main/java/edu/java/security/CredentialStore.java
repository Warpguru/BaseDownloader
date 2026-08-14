package edu.java.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.PostConstruct;
import javax.ejb.Schedule;
import javax.ejb.Singleton;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.java.application.Constants;
import edu.java.rest.ApiConstants;

/**
 * Singleton EJB that loads and caches the BaseDownloader credential store from a UTF-8 properties file on disk.
 *
 * <h2>Credential file format</h2>
 * <p>
 * One entry per line: {@code username=password:token}. Lines whose first non-whitespace character is {@code #} are treated as
 * comments and ignored. Blank lines are also ignored. Example:
 * </p>
 * 
 * <pre>
 * # BaseDownloader credentials
 * admin=s3cr3t:mytoken123
 * readonly=pass1:tok456
 * </pre>
 *
 * <h2>File location</h2>
 * <p>
 * The path is read from the MicroProfile Config property {@code bd.credentials.file}, which is mapped in {@code server.xml} as
 * a Liberty {@code <variable>} element. The default value points to {@code ${server.config.dir}/bd-credentials.properties} —
 * Liberty's configuration directory, which is outside the WAR and survives redeployment. Operators can override the path
 * without rebuilding the application.
 * </p>
 *
 * <h2>Periodic reload</h2>
 * <p>
 * {@link #reloadCredentials()} is triggered every minute by an EJB timer ({@code @Schedule}). The loaded map is swapped
 * atomically via an {@link AtomicReference} so concurrent reads always see a consistent, fully-populated snapshot — never a
 * partially-loaded map. Changes to the credential file therefore take effect within approximately one minute without requiring
 * a server restart.
 * </p>
 *
 * <h2>Security note (PoC)</h2>
 * <p>
 * Passwords and tokens are stored in <strong>plain text</strong> in this proof-of-concept implementation. In a production
 * deployment they should be replaced with salted hashes (e.g. BCrypt) and the comparison updated to use a constant-time hash
 * verification function.
 * </p>
 *
 * <h2>Why {@code @Singleton}?</h2>
 * <p>
 * The {@code @Schedule} annotation is only valid on {@code @Singleton} EJBs. The bean holds no per-request mutable state; the
 * entire credential map is replaced atomically on each reload.
 * </p>
 */
@Singleton
public class CredentialStore {

    private static Logger logger = LoggerFactory.getLogger(CredentialStore.class);

    // -------------------------------------------------------------------------
    // Internal value type — holds the password:token pair for one user
    // -------------------------------------------------------------------------

    /**
     * Immutable pair of password and token for a single user. Both values are stored as loaded from the file (plain text in
     * this PoC).
     */
    static final class Credential {
        final String password;
        final String token;

        Credential(final String password, final String token) {
            this.password = password;
            this.token = token;
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    @Inject
    @ConfigProperty(name = Constants.CONFIG_BD_CREDENTIALS_FILE)
    private String credentialFilePath;

    /**
     * Atomically-swapped snapshot of the credential map. Keys are usernames; values are the corresponding {@link Credential}.
     * The reference is never {@code null}; an empty map is used when the file cannot be read.
     */
    private final AtomicReference<Map<String, Credential>> credentialsRef = new AtomicReference<>(Collections.emptyMap());

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Loads the credential file immediately at startup. Called by the EJB container after injection is complete.
     */
    @PostConstruct
    public void initialLoad() {
        reloadCredentials();
    }

    /**
     * Re-reads the credential file and atomically replaces the in-memory map.
     * <p>
     * Triggered every minute by the EJB timer service. If the file cannot be read (e.g. it was temporarily moved), the existing
     * map is retained unchanged and a warning is printed to stdout. This means the last successfully loaded credentials remain
     * active until the file becomes readable again.
     * </p>
     * <p>
     * {@code persistent = false} prevents Liberty from persisting the timer across restarts; the timer is simply recreated from
     * the annotation when the server starts.
     * </p>
     */
    @Schedule(hour = "*", minute = "*/1", second = "0", persistent = false)
    public void reloadCredentials() {
        logger.info("CredentialStore (re)loading ...");
        final Map<String, Credential> loaded = new HashMap<>();
        try (final BufferedReader reader = Files.newBufferedReader(Paths.get(credentialFilePath), StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                final String trimmed = line.trim();
                // Skip blank lines and comments
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                // Expected format: username=password:token
                final int equalsIdx = trimmed.indexOf('=');
                final int colonIdx = trimmed.indexOf(':', equalsIdx + 1);
                if (equalsIdx < 1 || colonIdx < equalsIdx + 1) {
                    logger.warn("Ignoring malformed line {} in {}", lineNumber, credentialFilePath);
                    continue;
                }
                final String username = trimmed.substring(0, equalsIdx).trim();
                final String password = trimmed.substring(equalsIdx + 1, colonIdx).trim();
                final String token = trimmed.substring(colonIdx + 1).trim();
                if (username.isEmpty() || password.isEmpty() || token.isEmpty()) {
                    logger.warn("Ignoring entry with empty field at line {} in {}", lineNumber, credentialFilePath);
                    continue;
                }
                loaded.put(username, new Credential(password, token));
            }
            credentialsRef.set(Collections.unmodifiableMap(loaded));
            logger.info("Loaded {} credential(s) from {}", loaded.size(), credentialFilePath);
        } catch (IOException e) {
            logger.warn("Could not read credential file {} — retaining previous credentials: {}", credentialFilePath,
                    e.getMessage());
        } finally {
            logger.info("CredentialStore (re)loaded");
        }
    }

    // -------------------------------------------------------------------------
    // Validation methods — called by AuthFilter and LoginController
    // -------------------------------------------------------------------------

    /**
     * Validates a username + password + token triple against the loaded credential store.
     * <p>
     * All three fields must be non-null and non-empty and must match a stored entry exactly (case-sensitive).
     * </p>
     *
     * @param username the submitted username
     * @param password the submitted password
     * @param token    the submitted API token
     * @return {@code true} if the triple matches a stored credential; {@code false} otherwise
     */
    public boolean validateTriple(final String username, final String password, final String token) {
        if (username == null || password == null || token == null) {
            return false;
        }
        final Credential stored = credentialsRef.get().get(username);
        return stored != null && stored.password.equals(password) && stored.token.equals(token);
    }

    /**
     * Validates an HTTP {@code Authorization} header value (Basic or Bearer scheme).
     *
     * <h2>Accepted formats</h2>
     * <ul>
     * <li>{@code Basic <base64(username:password)>} — the Base64 payload is decoded and the username matched against the store;
     * the password must match the stored password. The token field is <strong>not</strong> checked for Basic auth (it is not
     * present in the standard Basic header).</li>
     * <li>{@code Bearer <token>} — the token is matched against every stored credential's token field. Username and password
     * are not required.</li>
     * </ul>
     *
     * @param authorizationHeader the raw value of the {@code Authorization} HTTP header, or {@code null} if the header is
     *                            absent
     * @return the authenticated username if the header is valid, or {@code null} otherwise
     */
    public String validateAuthorizationHeader(final String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.trim().isEmpty()) {
            return null;
        }
        final String[] parts = authorizationHeader.trim().split("\\s+", 2);
        if (parts.length != 2) {
            return null;
        }
        final String scheme = parts[0];
        final String payload = parts[1];

        if (ApiConstants.AUTH_SCHEME_BASIC.equalsIgnoreCase(scheme)) {
            return validateBasic(payload);
        }
        if (ApiConstants.AUTH_SCHEME_BEARER.equalsIgnoreCase(scheme)) {
            return validateBearer(payload);
        }
        return null;
    }

    /**
     * Decodes a Base64 Basic-auth payload ({@code base64(username:password)}) and validates it against the credential store.
     *
     * <p>
     * <strong>Legacy fallback:</strong> if the decoded credential matches the built-in PoC token (e.g. {@code BD:1.0.0},
     * composed as {@link Constants#APPLICATON}{@code :}{@link Constants#APP_VERSION}), it is accepted even when no matching
     * entry exists in the credential file. This allows the OpenAPI UI and integration tests to authenticate without editing the
     * credential file.
     * </p>
     *
     * @param base64Payload the Base64-encoded {@code username:password} string
     * @return the authenticated username, or {@code null} if invalid
     */
    private String validateBasic(final String base64Payload) {
        final String legacyToken = Constants.APPLICATON + ":" + Constants.APP_VERSION;
        try {
            final String decoded = new String(Base64.getDecoder().decode(base64Payload), StandardCharsets.UTF_8);
            final int colonIdx = decoded.indexOf(':');
            if (colonIdx < 1) {
                return null;
            }
            final String username = decoded.substring(0, colonIdx);
            final String password = decoded.substring(colonIdx + 1);
            // Primary check: credential file
            final Credential stored = credentialsRef.get().get(username);
            if (stored != null && stored.password.equals(password)) {
                return username;
            }
            // Legacy fallback: e.g. BD:1.0.0 built-in PoC credential
            if (legacyToken.equals(decoded)) {
                logger.warn("Authenticated via administrative Basic credential ({})!", legacyToken);
                return Constants.APPLICATON;
            }
        } catch (IllegalArgumentException e) {
            // Base64 decode failed — not a valid Basic token
        }
        return null;
    }

    /**
     * Validates a Bearer token against the token field of every stored credential.
     *
     * <p>
     * <strong>Legacy fallback:</strong> after the credential-file scan, the token is matched against the built-in PoC
     * credential (e.g. {@code BD:1.0.0}, composed as {@link Constants#APPLICATON}{@code :}{@link Constants#APP_VERSION}) in two
     * ways:
     * </p>
     * <ol>
     * <li>Raw — the token is compared directly (e.g. {@code Authorization: Bearer BD:1.0.0}).</li>
     * <li>Base64-decoded — the token is Base64-decoded first, then compared (e.g. {@code Authorization: Bearer QkQ6MS4wLjA=} as
     * sent by the OpenAPI UI, which encodes Bearer tokens the same way it encodes Basic credentials).</li>
     * </ol>
     * <p>
     * The credential-file entries are never matched against a Base64-decoded token — real tokens in the credential file are
     * always plain text.
     * </p>
     *
     * @param token the Bearer token value (raw, as received in the header)
     * @return the username whose token matches, or {@code null} if none matches
     */
    private String validateBearer(final String token) {
        // Primary check: credential file — raw token
        for (final Map.Entry<String, Credential> entry : credentialsRef.get().entrySet()) {
            if (entry.getValue().token.equals(token)) {
                return entry.getKey();
            }
        }
        // Legacy fallback: e.g. BD:1.0.0 — Base64-decoded
        final String legacyToken = Constants.APPLICATON + ":" + Constants.APP_VERSION;
        try {
            final String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            if (legacyToken.equals(decoded)) {
                logger.warn("Authenticated via administrative Bearer token ({})!", legacyToken);
                return Constants.APPLICATON;
            }
        } catch (IllegalArgumentException e) {
            // Not valid Base64 — not the legacy token
        }
        return null;
    }

}
