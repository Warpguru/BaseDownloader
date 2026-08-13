package edu.java.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;

/**
 * Immutable value object that pairs a Base64-encoded chunk of a downloaded resource with the CRC32 and MD5 checksums of that
 * encoded text.
 *
 * <h2>Why checksum the Base64 text, not the decoded bytes?</h2>
 * <p>
 * The user downloads the chunk as a plain-text file and can verify it with OS tools <em>before</em> attempting reassembly and
 * decoding. If the checksum covered the decoded bytes the user would have to decode first — defeating the purpose. Verifying
 * the Base64 file they actually received is always the right first step.
 * </p>
 *
 * <h2>Why both CRC32 and MD5?</h2>
 * <p>
 * CRC32 is extremely fast, produces a short 8-character hex value, and is natively available in every operating system (e.g.
 * {@code cksum} on Linux, {@code Get-FileHash -Algorithm CRC32} via PowerShell, and many ZIP utilities on Windows). MD5 is the
 * de-facto standard for file-integrity verification: {@code md5sum {file}} on Linux/macOS and
 * {@code certutil -hashfile {file} MD5} on Windows. Providing both means any user can verify a chunk with the tools already on
 * their machine.
 * </p>
 */
public class DownloadChunk {

    /** The Base64-encoded text content of this chunk. */
    private final String base64Content;

    /**
     * CRC32 checksum of {@link #base64Content} as an 8-character zero-padded lowercase hex string (e.g. {@code "0a3f7c21"}).
     */
    private final String crc32Hex;

    /**
     * MD5 checksum of {@link #base64Content} as a 32-character lowercase hex string (e.g.
     * {@code "d41d8cd98f00b204e9800998ecf8427e"}).
     */
    private final String md5Hex;

    /**
     * Constructs a {@code DownloadChunk} and computes both checksums immediately.
     *
     * @param base64Content the Base64-encoded text of this chunk; must not be {@code null}
     */
    public DownloadChunk(final String base64Content) {
        this.base64Content = base64Content;
        final byte[] contentBytes = base64Content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.crc32Hex = computeCrc32Hex(contentBytes);
        this.md5Hex = computeMd5Hex(contentBytes);
    }

    /**
     * Returns the Base64-encoded text content of this chunk.
     *
     * @return base64 text
     */
    public String getBase64Content() {
        return base64Content;
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

    private static String computeCrc32Hex(final byte[] bytes) {
        final CRC32 crc = new CRC32();
        crc.update(bytes);
        return String.format("%08x", crc.getValue());
    }

    private static String computeMd5Hex(final byte[] bytes) {
        try {
            final MessageDigest md = MessageDigest.getInstance("MD5");
            final byte[] digest = md.digest(bytes);
            final StringBuilder sb = new StringBuilder(32);
            for (final byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 is guaranteed by the Java SE specification; never reached
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }

}
