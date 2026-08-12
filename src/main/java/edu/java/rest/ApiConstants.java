package edu.java.rest;

/**
 * SAPs for {@code Restful} WebService endpoints.
 */
public class ApiConstants {

    /** Application name. */
    public final static String APPLICATON = "BD";

    /** Context root for {@link Application}. */
    public final static String RESOURCE_API_APPLICATON = "api";

    /** Context root for {@link LoginController}. */
    public final static String RESOURCE_API_LOGIN = "login";

    /** Context root for {@link InfoController}. */
    public final static String RESOURCE_API_INFO = "info";

    /** Context root for {@link DownloadController}. */
    public final static String RESOURCE_API_BASE = "base";

    /** Context root for {@link DownloadAsyncController}. */
    public final static String RESOURCE_API_DOWNLOAD = "download";

    /** Response header carrying the UUID of a newly submitted download task. */
    public final static String HEADER_X_BD_UUID = "X-BD-UUID";

    /** Response header carrying a human-readable message on authentication failure. */
    public final static String HEADER_X_BD_MESSAGE = "X-BD-Message";

    /**
     * Number of original binary bytes accumulated before a chunk boundary is created during an asynchronous chunked download.
     * <p>
     * Once this many raw bytes have been read from the remote resource, the accumulated bytes are Base64-encoded and stored as
     * one chunk entry in {@code DownloadTask.chunks}. The resulting Base64 text for a full chunk will be approximately
     * 37&nbsp;% larger than the source binary (~&nbsp;1.37&nbsp;MB of text per 1&nbsp;MB of binary). The last chunk of any
     * download is allowed to be smaller.
     * </p>
     */
    // Smaller chunks during testing
    public final static int CHUNK_SIZE_BYTES = 1 << 16;
    // public final static int CHUNK_SIZE_BYTES = 1_048_576;

}
