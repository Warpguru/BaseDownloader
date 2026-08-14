package edu.java.rest;

import edu.java.application.Constants;

/**
 * Constants for JAX-RS REST endpoints: HTTP response-header names, URL path segments,
 * authentication scheme names, and the chunk-size tuning value.
 *
 * <p>
 * Application-wide metadata (display name, version, GitHub URL, config-property keys, and file-name suffixes) live in
 * {@link Constants}. Only REST-layer concerns belong here.
 * </p>
 */
public final class ApiConstants {

    // -------------------------------------------------------------------------
    // Authentication scheme literals
    // -------------------------------------------------------------------------

    /**
     * HTTP authentication scheme name for Basic authentication ({@code "Basic"}).
     * Used in {@code Authorization} header parsing and OpenAPI security-scheme declarations.
     */
    public static final String AUTH_SCHEME_BASIC = "Basic";

    /**
     * HTTP authentication scheme name for Bearer authentication ({@code "Bearer"}).
     * Used in {@code Authorization} header parsing and OpenAPI security-scheme declarations.
     */
    public static final String AUTH_SCHEME_BEARER = "Bearer";

    /**
     * OpenAPI / MicroProfile security-scheme name for Basic authentication.
     * Must match the {@code securitySchemeName} in {@link edu.java.application.Application}
     * and every {@code @SecurityRequirement(name = ...)} annotation that references it.
     */
    public static final String SECURITY_SCHEME_BASIC = "BasicAuthentication";

    /**
     * OpenAPI / MicroProfile security-scheme name for Bearer authentication.
     * Must match the {@code securitySchemeName} in {@link edu.java.application.Application}
     * and every {@code @SecurityRequirement(name = ...)} annotation that references it.
     */
    public static final String SECURITY_SCHEME_BEARER = "BearerAuthentication";

    // -------------------------------------------------------------------------
    // API path segments
    // -------------------------------------------------------------------------

    /**
     * JAX-RS {@code @ApplicationPath} value — delegates to {@link Constants#API_BASE} so the path is defined in exactly one
     * place.
     */
    public static final String RESOURCE_API_APPLICATON = Constants.API_BASE;

    /** Path segment for {@code LoginController} login form/submit ({@code /api/login}). */
    public static final String RESOURCE_API_LOGIN = "login";

    /** Path segment for the logout endpoint ({@code /api/login/logout}). */
    public static final String RESOURCE_API_LOGOUT = "login/logout";

    /** Path segment for {@code InfoController} ({@code /api/info}). */
    public static final String RESOURCE_API_INFO = "info";

    /** Path segment for {@code DownloadController} ({@code /api/download}). */
    public static final String RESOURCE_API_DOWNLOAD = "download";

    /** Path segment for {@code DownloadAsyncController} ({@code /api/asyncdownload}). */
    public static final String RESOURCE_API_ASYNCDOWNLOAD = "asyncdownload";

    /** Response header carrying the UUID of a newly submitted download task. */
    public static final String HEADER_X_BD_UUID = "X-BD-UUID";

    /** Response header carrying a human-readable message on authentication failure. */
    public static final String HEADER_X_BD_MESSAGE = "X-BD-Message";

    /** Response header carrying the CRC32 checksum (8-char lowercase hex) of a downloaded chunk. */
    public static final String HEADER_X_BD_CRC32 = "X-BD-CRC32";

    /** Response header carrying the MD5 checksum (32-char lowercase hex) of a downloaded chunk. */
    public static final String HEADER_X_BD_MD5 = "X-BD-MD5";

    /** Response header carrying the SHA-256 checksum (64-char lowercase hex) of a downloaded chunk. */
    public static final String HEADER_X_BD_SHA256 = "X-BD-SHA256";

    /**
     * Number of original binary bytes accumulated before a chunk boundary is created during an asynchronous chunked download.
     * <p>
     * Once this many raw bytes have been read from the remote resource, the accumulated bytes are Base64-encoded and stored as
     * one chunk entry. The resulting Base64 text for a full chunk will be approximately 37&nbsp;% larger than the source binary
     * (~&nbsp;1.37&nbsp;MB of text per 1&nbsp;MB of binary). The last chunk of any download is allowed to be smaller.
     * </p>
     */
    public static final int CHUNK_SIZE_BYTES = 1 << 18;

    /**
     * Hidden constructor.
     */
    private ApiConstants() {
    }

}
