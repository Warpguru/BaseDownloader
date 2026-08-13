package edu.java.application;

/**
 * Application-wide string constants shared across all packages.
 *
 * <p>
 * This class is the single source of truth for every "magic string" used by more than one class: MicroProfile Config property
 * keys, Liberty context-root path fragments, file-name suffixes, and application metadata (display name, version, URLs).
 * Controllers, services, and the JAX-RS {@link Application} class all import from here so that a rename or move can be done in
 * one place.
 * </p>
 *
 * <h2>Design rules</h2>
 * <ul>
 * <li>All constants are {@code public static final String} (or {@code int}) — no instances.</li>
 * <li>Config-property key constants are prefixed {@code CONFIG_} to make their purpose obvious at the call site (e.g.
 * {@code @ConfigProperty(name = Constants.CONFIG_BD_CHUNK_DIR)}).</li>
 * <li>Path-fragment constants are prefixed {@code PATH_} and contain only the bare segment (no leading or trailing slash) so
 * they can be composed freely.</li>
 * <li>Display strings and URLs are prefixed {@code APP_}.</li>
 * </ul>
 */
public final class Constants {

    // -------------------------------------------------------------------------
    // MicroProfile Config property keys (map to Liberty <variable> elements)
    // -------------------------------------------------------------------------

    /**
     * MicroProfile Config property key for the Base64 chunk storage directory.
     * <p>
     * Mapped in {@code server.xml} as:
     * {@code <variable name="bd.chunk.dir" defaultValue="${java.io.tmpdir}/Base-Downloader"/>}. If an operator does not
     * override it, Liberty expands {@code ${java.io.tmpdir}} to the JVM temporary directory at startup.
     * </p>
     */
    public static final String CONFIG_BD_CHUNK_DIR = "bd.chunk.dir";

    /**
     * MicroProfile Config property key for the path to the credential properties file.
     * <p>
     * Mapped in {@code server.xml} as:
     * {@code <variable name="bd.credentials.file" defaultValue="${server.config.dir}/bd-credentials.properties"/>}.
     * {@code ${server.config.dir}} is Liberty's configuration directory — outside the WAR, fully
     * operator-editable and surviving redeployment. The file is re-read periodically so changes
     * take effect without a server restart.
     * </p>
     */
    public static final String CONFIG_BD_CREDENTIALS_FILE = "bd.credentials.file";

    // -------------------------------------------------------------------------
    // File-system / file-naming conventions
    // -------------------------------------------------------------------------

    /**
     * Extension appended to every on-disk chunk file and to the user-facing {@code Content-Disposition} filename (e.g.
     * {@code data.zip.1.txt}).
     * <p>
     * The {@code .txt} extension is intentional: Base64 output is pure ASCII and any text editor or shell command ({@code cat},
     * {@code type}, {@code certutil}) can open the file without charset conversion. Keeping the same extension on disk and in
     * the download link establishes a 1:1 correspondence that aids debugging and manual recovery.
     * </p>
     */
    public static final String CHUNK_FILE_EXTENSION = ".txt";

    // -------------------------------------------------------------------------
    // Web application context root and API path fragments
    // -------------------------------------------------------------------------

    /**
     * Servlet context root as deployed on Liberty (matches {@code contextRoot} in {@code server.xml} and the WAR artifact id in
     * {@code pom.xml}). Used to build absolute href values in HTML responses.
     */
    public static final String CONTEXT_ROOT = "/base-downloader";

    /**
     * JAX-RS application base path (value of {@code @ApplicationPath}). Controllers are mounted under
     * {@code CONTEXT_ROOT + "/" + API_BASE}.
     */
    public static final String API_BASE = "api";

    // -------------------------------------------------------------------------
    // Application metadata
    // -------------------------------------------------------------------------

    /**
     * Human-readable application display name shown in HTML pages and OpenAPI metadata.
     */
    public static final String APP_DISPLAY_NAME = "BaseDownloader";

    /**
     * Application version string. Reflected in the OpenAPI {@code info.version} field and in HTML page titles.
     */
    public static final String APP_VERSION = "1.0.0";

    /**
     * Contact name shown in OpenAPI metadata.
     */
    public static final String APP_CONTACT_NAME = "Roman Stangl";

    /**
     * Contact e-mail address shown in OpenAPI metadata.
     */
    public static final String APP_CONTACT_EMAIL = "Roman.Stangl@gmx.net";

    /**
     * GitHub repository URL. Used in the OpenAPI {@code info}, {@code termsOfService}, and {@code externalDocs} fields, as well
     * as in HTML page footers (Task 13).
     */
    public static final String APP_GITHUB_URL = "https://github.com/Warpguru/BaseDownloader";

    // -------------------------------------------------------------------------
    // Legacy / PoC credential (kept for reference; no longer used at runtime)
    // -------------------------------------------------------------------------

    /**
     * Hard-coded API password used by the legacy {@code DownloadController} before Task 12
     * introduced the file-based {@code CredentialStore}.  Retained as a named constant so that
     * the default {@code bd-credentials.properties} sample file can reference its value in a
     * comment; it is no longer read at runtime.
     *
     * <p>
     * <strong>Security note:</strong> this value is a PoC placeholder.  Production deployments
     * must use a real credential file managed outside the WAR.
     * </p>
     */
    public static final String LEGACY_API_PASSWORD = "1.0.0";

    /**
     * Hidden constructor.
     */
    private Constants() {
    }

}
