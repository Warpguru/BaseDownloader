package edu.java.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;

/**
 * Immutable metadata record for one Base64-encoded chunk of a downloaded resource.
 * <p>
 * The Base64 content is <strong>not</strong> retained in memory after construction. For a 100 MB binary the corresponding
 * Base64 text is approximately 137 MB; with multiple concurrent downloads the JVM heap would be exhausted if every chunk were
 * kept in memory. Instead the content is written to disk immediately via {@link ChunkStorageService#writeChunk} and the object
 * retains only the metadata needed to locate and verify it (UUID, index, filename, checksums). {@link #getBase64Content()}
 * reads the file on demand.
 * </p>
 *
 * <h2>Why checksum the Base64 text, not the decoded bytes?</h2>
 * <p>
 * The user downloads the chunk as a plain-text file and can verify it with OS tools <em>before</em> attempting reassembly and
 * decoding. Verifying the Base64 file they actually received is always the right first step.
 * </p>
 *
 * <h2>Why CRC32, MD5, and SHA-256?</h2>
 * <p>
 * CRC32 is fast and universally available on Linux/macOS ({@code cksum}); MD5 is the traditional file-integrity standard
 * ({@code md5sum}, {@code certutil -hashfile ... MD5}); SHA-256 is the current security-grade standard ({@code sha256sum},
 * {@code certutil -hashfile ... SHA256}, supported by 7-Zip and most modern archive tools). Providing all three gives users the
 * widest choice of verification tool without recomputing on their end.
 * </p>
 */
public class DownloadChunk {

    /** Storage service used by {@link #getBase64Content()} to read the chunk file on demand. */
    private final ChunkStorageService chunkStorageService;

    /** UUID of the owning task; used to locate the chunk file on disk. */
    private final String uuid;

    /** 1-based chunk index; used to locate the chunk file on disk and matches the user-visible index. */
    private final int chunkIndex;

    /** Original filename of the downloaded resource; used to locate the chunk file on disk. */
    private final String originalFileName;

    /**
     * CRC32 checksum of the Base64 content as an 8-character zero-padded lowercase hex string (e.g. {@code "0a3f7c21"}).
     */
    private final String crc32Hex;

    /**
     * MD5 checksum of the Base64 content as a 32-character lowercase hex string (e.g.
     * {@code "d41d8cd98f00b204e9800998ecf8427e"}).
     */
    private final String md5Hex;

    /**
     * SHA-256 checksum of the Base64 content as a 64-character lowercase hex string.
     */
    private final String sha256Hex;

    /**
     * Constructs a {@code DownloadChunk}, computes checksums, and immediately persists {@code base64Content} to disk via
     * {@link ChunkStorageService#writeChunk}. The content string is not stored in this object after construction.
     *
     * @param uuid                UUID of the owning download task
     * @param chunkIndex          1-based chunk index
     * @param originalFileName    original filename from the URL path
     * @param base64Content       the Base64-encoded text of this chunk
     * @param chunkStorageService the chunkStorageService service used to persist and later read the chunk file
     * @throws IOException if the chunk file cannot be written
     */
    public DownloadChunk(final String uuid, final int chunkIndex, final String originalFileName, final String base64Content,
            final ChunkStorageService storage) throws IOException {
        this.uuid = uuid;
        this.chunkIndex = chunkIndex;
        this.originalFileName = originalFileName;
        this.chunkStorageService = storage;
        final byte[] contentBytes = base64Content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.crc32Hex = computeCrc32Hex(contentBytes);
        this.md5Hex = computeDigestHex("MD5", contentBytes);
        this.sha256Hex = computeDigestHex("SHA-256", contentBytes);
        storage.writeChunk(uuid, chunkIndex, originalFileName, base64Content);
    }

    /**
     * Reads and returns the Base64-encoded text content of this chunk from disk.
     *
     * @return the Base64 text
     * @throws IOException if the chunk file cannot be read
     */
    public String getBase64Content() throws IOException {
        return chunkStorageService.readChunk(uuid, chunkIndex, originalFileName);
    }

    /**
     * Returns the CRC32 checksum of the Base64 content as an 8-character zero-padded lowercase hex string.
     *
     * @return CRC32 hex string
     */
    public String getCrc32Hex() {
        return crc32Hex;
    }

    /**
     * Returns the MD5 checksum of the Base64 content as a 32-character lowercase hex string.
     *
     * @return MD5 hex string
     */
    public String getMd5Hex() {
        return md5Hex;
    }

    /**
     * Returns the SHA-256 checksum of the Base64 content as a 64-character lowercase hex string.
     *
     * @return SHA-256 hex string
     */
    public String getSha256Hex() {
        return sha256Hex;
    }

    private static String computeCrc32Hex(final byte[] bytes) {
        final CRC32 crc = new CRC32();
        crc.update(bytes);
        return String.format("%08x", crc.getValue());
    }

    /**
     * Computes a hex-encoded digest using the named {@link MessageDigest} algorithm. Both MD5 and SHA-256 are guaranteed by the
     * Java SE specification.
     *
     * @param algorithm JCA algorithm name (e.g. {@code "MD5"}, {@code "SHA-256"})
     * @param bytes     input bytes
     * @return lowercase hex string of the digest
     */
    private static String computeDigestHex(final String algorithm, final byte[] bytes) {
        try {
            final MessageDigest md = MessageDigest.getInstance(algorithm);
            final byte[] digest = md.digest(bytes);
            final StringBuilder sb = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 and SHA-256 are guaranteed by the Java SE specification; never reached
            throw new IllegalStateException(algorithm + " algorithm not available", e);
        }
    }

}
