package edu.java.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Holds all state for a single asynchronous Base64-chunked download request.
 *
 * <h2>Why chunks?</h2>
 * <p>
 * Corporate firewalls and web proxies frequently block or truncate binary HTTP responses (e.g.
 * {@code application/octet-stream}) while allowing plain-text responses to pass through without inspection. By Base64-encoding
 * the downloaded resource and splitting it into plain-text chunks, each chunk can be retrieved individually as a
 * {@code text/plain} HTTP response, bypassing those restrictions. The client reassembles the original binary by concatenating
 * all chunks and decoding the combined Base64 string.
 * </p>
 *
 * <h2>Why a {@link List} for chunks?</h2>
 * <p>
 * Chunks must be reassembled in the exact order they were produced; a {@link List} preserves insertion order so that
 * index-based retrieval ({@code GET /api/download/{uuid}/{index}}) maps deterministically to the corresponding segment of the
 * encoded stream.
 * </p>
 *
 * <h2>Expiry and the scheduler</h2>
 * <p>
 * {@link #expiresAt} is set to one hour after submission. The {@code DownloadCleanupScheduler} (Task 7) calls
 * {@code DownloadTaskRegistry.removeExpired()} every minute; any task whose {@link #expiresAt} is in the past is evicted from
 * the registry, releasing the chunk strings from memory.
 * </p>
 */
public class DownloadTask {

    /**
     * Lifecycle states of a download task.
     */
    public enum Status {
        /** Task has been registered but the background thread has not started yet. */
        PENDING,
        /** The background thread is actively downloading and encoding. */
        IN_PROGRESS,
        /** Download completed successfully; all chunks are available. */
        DONE,
        /** Download failed; see {@link DownloadTask#errorMessage} for details. */
        FAILED
    }

    /** Unique identifier of download assigned at construction. */
    private String uuid;

    /** The URL string submitted by the user ({@code http://}, {@code https://}, or {@code ftp://}). */
    private String requestedUrl;

    /** Filename extracted from the last path segment of {@link #requestedUrl} (e.g. {@code data.zip}). */
    private String originalFileName;

    /**
     * Ordered list of Base64-encoded text chunks produced by the background download. Each entry except the last represents
     * exactly {@code ApiConstants.CHUNK_SIZE_BYTES} of original binary data; the last entry may be smaller.
     */
    private List<String> chunks;

    /**
     * Total number of chunks once the download is complete, or {@code -1} while the download is still running. Set to
     * {@code chunks.size()} when {@link #status} transitions to {@link Status#DONE} or {@link Status#FAILED}.
     */
    private int totalChunks;

    /** Current lifecycle {@link Status} of this task. */
    private Status status;

    /**
     * Human-readable error message, or {@code null} unless {@link #status} is {@link Status#FAILED}.
     */
    private String errorMessage;

    /** Timestamp at which this task was created. */
    private Instant submittedAt;

    /**
     * Timestamp after which this task may be evicted by the cleanup scheduler. Set to one hour after {@link #submittedAt}.
     */
    private Instant expiresAt;

    /**
     * Constructs a new {@code DownloadTask} in {@link Status#PENDING} state.
     *
     * @param requestedUrl     URL string submitted by the user
     * @param originalFileName filename derived from the URL path (last path segment)
     */
    public DownloadTask(final String requestedUrl, final String originalFileName) {
        this.uuid = UUID.randomUUID().toString();
        this.requestedUrl = requestedUrl;
        this.originalFileName = originalFileName;
        this.chunks = new ArrayList<>();
        this.totalChunks = -1;
        this.status = Status.PENDING;
        this.errorMessage = null;
        this.submittedAt = Instant.now();
        this.expiresAt = this.submittedAt.plus(1, ChronoUnit.HOURS);
    }

    /**
     * Add a non-null {@code chunk} to {@link DownloadTask#chunks}.
     * 
     * @param chunk to add
     * @return {@link DownloadTask}
     */
    public DownloadTask add(final String chunk) {
        if (chunk != null) {
            chunks.add(chunk);
        }
        return this;
    }

    /**
     * Retrieve the number of {@link DownloadTask#chunks}.
     * 
     * @return numberOfChunks
     */
    public int getNumberOfChunks() {
        return chunks.size();
    }

    /**
     * Set the number of chunks processed to {@link DownloadTask#totalChunks}.
     * 
     * @param totalChunks to set
     * @return {@link DownloadTask}
     */
    public DownloadTask setNumberOfTotalChunks(final int totalChunks) {
        this.totalChunks = totalChunks;
        return this;
    }

    /**
     * Retrieve the {@link DownloadTask#chunks} of download request.
     * 
     * @return uuid
     */
    public String getUuid() {
        return uuid;
    }

    /**
     * Get {@link DownloadTask#requestedUrl} of download request.
     * 
     * @return requestedUrl
     */
    public String getRequestedUrl() {
        return requestedUrl;
    }

    /**
     * Retrieve the original filename derived from the URL path.
     *
     * @return originalFileName
     */
    public String getOriginalFileName() {
        return originalFileName;
    }

    /**
     * Returns the Base64-encoded chunk at the given 0-based index, or {@code null} if the index is out of range or the chunk
     * has not yet been produced by the background download.
     *
     * @param index 0-based chunk index
     * @return the Base64 chunk string, or {@code null} if not available
     */
    public String getChunk(final int index) {
        if (index < 0 || index >= chunks.size()) {
            return null;
        }
        return chunks.get(index);
    }

    /**
     * Retrieve the total number of chunks once complete, or {@code -1} while still running.
     *
     * @return totalChunks
     */
    public int getTotalChunks() {
        return totalChunks;
    }

    /**
     * Set {@link DownloadTask#status} for download request.
     * 
     * @param status
     */
    public void setStatus(final Status status) {
        this.status = status;
    }

    /**
     * Get {@link DownloadTask#status} of download request.
     * 
     * @return status
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Set error message when download request failed.
     * 
     * @param errorMessage
     */
    public void setErrorMessage(final String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * Retrieve error message when download request failed.
     * 
     * @return errorMessage
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Retrieve when download request was submitted.
     * 
     * @return submittedAt
     */
    public Instant getSubmittedAt() {
        return submittedAt;
    }

    /**
     * Retrieve when download request expires.
     *
     * @return expiresAt
     */
    public Instant getExpiresAt() {
        return expiresAt;
    }

}
