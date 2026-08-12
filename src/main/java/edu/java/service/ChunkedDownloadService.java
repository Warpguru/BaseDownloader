package edu.java.service;

import java.io.BufferedInputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Base64;

import javax.ejb.Asynchronous;
import javax.ejb.Stateless;
import javax.inject.Inject;

import edu.java.rest.ApiConstants;

/**
 * EJB service that downloads a remote resource in the background and stores it as an ordered list of Base64-encoded text chunks
 * inside a {@link DownloadTask}.
 *
 * <h2>Why {@code @Asynchronous}?</h2>
 * <p>
 * Annotating {@link #startDownload(DownloadTask)} with {@link Asynchronous} causes the EJB container to dispatch the method
 * body onto a managed thread from its async thread pool. The calling HTTP request thread therefore returns immediately after
 * registering the task and receives the UUID in the 202 Accepted response, while the actual download runs in the background.
 * Without this annotation the HTTP request would block for the full duration of the download — potentially minutes for large
 * files — before any response could be sent to the browser.
 * </p>
 *
 * <h2>3-byte alignment rule for Base64</h2>
 * <p>
 * Base64 encodes every group of 3 raw bytes into exactly 4 printable characters. If an intermediate block whose byte count is
 * <em>not</em> a multiple of 3 is encoded in isolation, the encoder appends one or two {@code =} padding characters. When such
 * padded blocks are later concatenated and fed to {@code certutil -decode} or {@code base64 -d} for reassembly, the interior
 * {@code =} characters are treated as end-of-stream markers and the rest of the data is silently discarded — corrupting the
 * output file. The clipboard buffer prevents this: across both the inner 1&nbsp;KB read loop and the outer chunk-boundary
 * logic, only the largest multiple-of-3 prefix of the accumulated bytes is encoded at any given step; the remaining 0–2 bytes
 * are carried forward to the next iteration. The {@code =} padding therefore appears only once, at the very end of the final
 * chunk of the entire download.
 * </p>
 *
 * <h2>Why chunks are kept in memory (not on disk)</h2>
 * <p>
 * Storing chunks as {@link String} entries in {@link DownloadTask#chunks} keeps the implementation simple: no temporary files
 * to create, name, or clean up, and no file-system concurrency issues. Memory is reclaimed automatically when the
 * {@code DownloadCleanupScheduler} (Task 7) removes expired tasks from {@link DownloadTaskRegistry}.
 * </p>
 *
 * <p>
 * This bean is {@code @Stateless} so the EJB container can pool instances and dispatch concurrent async invocations without the
 * serialisation that {@code @Singleton} would impose.
 * </p>
 */
@Stateless
public class ChunkedDownloadService {

    /** Size of the stream read buffer in bytes. */
    private static final int BUFFER_LENGTH_STREAM = 1024;

    @Inject
    private DownloadTaskRegistry registry;

    /**
     * Downloads the resource identified by {@link DownloadTask#requestedUrl} in a background thread managed by the EJB
     * container, splitting the content into Base64-encoded chunks of {@link ApiConstants#CHUNK_SIZE_BYTES} raw bytes each.
     * <p>
     * The method returns immediately to the caller; the download proceeds asynchronously. Progress can be tracked via
     * {@link DownloadTask#status} and {@link DownloadTask#chunks}.
     * </p>
     * <p>
     * On successful completion {@link DownloadTask#status} is set to {@link DownloadTask.Status#DONE} and
     * {@link DownloadTask#totalChunks} is set to {@code task.chunks.size()}. On any failure {@link DownloadTask#status} is set
     * to {@link DownloadTask.Status#FAILED} and {@link DownloadTask#errorMessage} holds the exception message. No files are
     * written to disk.
     * </p>
     *
     * @param downloadTask the registered {@link DownloadTask} to execute; must already be present in the {@link DownloadTaskRegistry}
     */
    @Asynchronous
    public void startDownload(final DownloadTask downloadTask) {
        downloadTask.setStatus(DownloadTask.Status.IN_PROGRESS);
        try {
            final URL url = new URL(downloadTask.getRequestedUrl());

            // Buffer chunkBuffer accumulates raw bytes until CHUNK_SIZE_BYTES is reached.
            // It grows dynamically as bytes are appended from the stream buffer.
            byte[] chunkBuffer = new byte[0];

            // Buffer clipboardBuffer carries 0-2 remainder bytes between 1 KB read iterations so that every Base64-encoded 
            // segment (except the very last) is a multiple of 3 bytes and therefore produces no interior '=' padding 
            // characters. 
            // Changes dynamically to contain the part of chunkBuffer not yet encoded.
            byte[] clipboardBuffer = new byte[0];

            byte[] streamBuffer = new byte[BUFFER_LENGTH_STREAM];
            int bytesRead;
            int readCount = 1;

            try (BufferedInputStream in = new BufferedInputStream(url.openStream())) {
                while ((bytesRead = in.read(streamBuffer, 0, BUFFER_LENGTH_STREAM)) != -1) {
                    System.out.println("ChunkedDownloadService: read " + readCount + ", bytes=" + bytesRead);
                    readCount++;

                    // Append the freshly read bytes to chunkBuffer.
                    byte[] newChunkBuffer = new byte[chunkBuffer.length + bytesRead];
                    System.arraycopy(chunkBuffer, 0, newChunkBuffer, 0, chunkBuffer.length);
                    System.arraycopy(streamBuffer, 0, newChunkBuffer, chunkBuffer.length, bytesRead);
                    chunkBuffer = newChunkBuffer;
                    Arrays.fill(streamBuffer, (byte) 0);

                    // When enough raw bytes for a full chunk have accumulated, encode and flush.
                    while (chunkBuffer.length >= ApiConstants.CHUNK_SIZE_BYTES) {
                        // Extract exactly CHUNK_SIZE_BYTES from the front of chunkBuffer.
                        // Prepend any leftover clipboard bytes from the previous encode step so
                        // that the combined length fed to the encoder is a multiple of 3.
                        int rawSize = ApiConstants.CHUNK_SIZE_BYTES + clipboardBuffer.length;
                        int encodeSize = (rawSize / 3) * 3;

                        byte[] encodeBuffer = new byte[encodeSize];
                        System.arraycopy(clipboardBuffer, 0, encodeBuffer, 0, clipboardBuffer.length);
                        System.arraycopy(chunkBuffer, 0, encodeBuffer, clipboardBuffer.length,
                                encodeSize - clipboardBuffer.length);

                        // New clipboard = bytes from chunkBuffer not yet encoded.
                        int newClipSize = rawSize - encodeSize;
                        clipboardBuffer = new byte[newClipSize];
                        if (newClipSize > 0) {
                            System.arraycopy(chunkBuffer, encodeSize - clipboardBuffer.length, clipboardBuffer, 0, newClipSize);
                        }

                        downloadTask.add(Base64.getEncoder().encodeToString(encodeBuffer));
                        System.out.println("ChunkedDownloadService: emitted chunk " + downloadTask.getNumberOfChunks());

                        // Slide chunkBuffer forward past the bytes just consumed.
                        int consumed = ApiConstants.CHUNK_SIZE_BYTES;
                        byte[] remainder = new byte[chunkBuffer.length - consumed];
                        System.arraycopy(chunkBuffer, consumed, remainder, 0, remainder.length);
                        chunkBuffer = remainder;
                    }
                }
            }

            // Stream exhausted — encode whatever remains (clipboard + chunkBuffer tail).
            byte[] finalRaw = new byte[clipboardBuffer.length + chunkBuffer.length];
            System.arraycopy(clipboardBuffer, 0, finalRaw, 0, clipboardBuffer.length);
            System.arraycopy(chunkBuffer, 0, finalRaw, clipboardBuffer.length, chunkBuffer.length);
            // The final encode may have padding — this is the only '=' that should appear.
            downloadTask.add(Base64.getEncoder().encodeToString(finalRaw));
            System.out.println("ChunkedDownloadService: emitted final chunk " + downloadTask.getNumberOfChunks());

            downloadTask.setStatus(DownloadTask.Status.DONE);
        } catch (Exception e) {
            e.printStackTrace();
            downloadTask.setStatus(DownloadTask.Status.FAILED);
            downloadTask.setErrorMessage(e.getMessage());
        } finally {
            downloadTask.setNumberOfTotalChunks(downloadTask.getNumberOfChunks());
        }
    }

}
