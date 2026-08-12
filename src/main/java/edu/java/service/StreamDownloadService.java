package edu.java.service;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URL;
import java.util.Arrays;
import java.util.Base64;

import javax.ejb.Stateless;

/**
 * EJB service that downloads a remote resource over HTTP/FTP, writes the raw binary to a local
 * file, and returns the entire content as a single Base64-encoded string.
 *
 * <h2>3-byte alignment requirement</h2>
 * <p>
 * Base64 encodes every 3 raw bytes into exactly 4 characters.  If a block whose byte count is
 * not a multiple of 3 is encoded in isolation, the encoder appends {@code =} padding characters.
 * When multiple encoded blocks are concatenated (as happens across loop iterations) those interior
 * {@code =} characters corrupt the output and make tools such as {@code certutil -decode} or
 * {@code base64 -d} fail.  The clipboard buffer prevents this: after each read, only the largest
 * multiple-of-3 prefix of {@code (clipboardBuffer + streamBuffer)} is encoded; the remaining
 * 0–2 bytes are held in {@code clipboardBuffer} and prepended to the next iteration.
 * </p>
 *
 * <h2>Clipboard buffer</h2>
 * <p>
 * {@code clipboardBuffer} carries the remainder bytes (0, 1, or 2 bytes) from one 1 KB read
 * into the next so that every encoded segment except the very last is a multiple of 3 bytes
 * and therefore produces no {@code =} padding in the middle of the stream.
 * </p>
 *
 * <h2>Output files</h2>
 * <p>
 * The binary output is written to a file named {@code fileName} and the Base64 output to
 * {@code fileName + ".b64"}, where {@code fileName} is the last path segment of the resource URL
 * (e.g. {@code data.zip} for {@code https://example.com/files/data.zip}).  Both files are always
 * overwritten on each invocation.  Writing them is a known proof-of-concept limitation: concurrent
 * requests will race on the same files (Gotcha 4 in AGENTS.md).  {@code @Stateless} does not fix
 * this race; it is resolved only when {@code ChunkedDownloadService} (Task 2) is used instead.
 * </p>
 *
 * <p>
 * This bean is {@code @Stateless} so the EJB container can serve concurrent requests from a pool
 * of instances without serialising access via the default write-lock that {@code @Singleton} would
 * impose.
 * </p>
 */
@Stateless
public class StreamDownloadService {

	private static final int BUFFER_LENGTH_STREAM = 1024;

	/**
	 * Downloads the resource at {@code urlOfResource}, writes the raw bytes to {@code fileName}
	 * and the Base64-encoded bytes to {@code fileName + ".b64"}, and returns the Base64 string.
	 *
	 * @param urlOfResource URL of the remote resource to download
	 * @param fileName      local filename derived from the URL path (last path segment); used as
	 *                      the binary output filename and as the stem of the Base64 output filename
	 * @return complete Base64 encoding of the downloaded resource
	 * @throws Exception if the resource cannot be opened or read
	 */
	public String downloadStream(final URL urlOfResource, final String fileName) throws Exception {
		// Clipboard of stream bytes not encoded during last chunk
		byte clipboardBuffer[] = new byte[0];
		// Offset in buffer to encode in Base64
		int bytesEncoded = 0;
		StringWriter base64StringWriter = new StringWriter();

		byte streamBuffer[] = new byte[BUFFER_LENGTH_STREAM];
		int bytesRead;

		int chunkCount = 1;
		try (BufferedInputStream in = new BufferedInputStream(urlOfResource.openStream());
				FileOutputStream fileOutputStream = new FileOutputStream(fileName)) {
			while ((bytesRead = in.read(streamBuffer, 0, BUFFER_LENGTH_STREAM)) != -1) {
				System.out.println("Read chunk: " + chunkCount + " containing bytes: " + bytesRead);
				chunkCount++;
				fileOutputStream.write(streamBuffer, 0, bytesRead);
				// Include data left unencoded in Base64 from previous stream buffer
				int clipboardBufferSizePrevious = clipboardBuffer.length;
				bytesEncoded = ((bytesRead + clipboardBufferSizePrevious) / 3) * 3;
				byte encodeBuffer[] = new byte[bytesEncoded];
				System.arraycopy(clipboardBuffer, 0, encodeBuffer, 0, clipboardBufferSizePrevious);
				System.arraycopy(streamBuffer, 0, encodeBuffer, clipboardBufferSizePrevious,
						(bytesEncoded - clipboardBufferSizePrevious));
				String encodedString = Base64.getEncoder().encodeToString(encodeBuffer);
				base64StringWriter.append(encodedString);
				// Copy still unencoded stream bytes into clipboard
				int clipboardBufferSize = (bytesRead + clipboardBufferSizePrevious) - bytesEncoded;
				clipboardBuffer = new byte[clipboardBufferSize];
				try {
					System.arraycopy(streamBuffer, (bytesEncoded - clipboardBufferSizePrevious), clipboardBuffer, 0,
							clipboardBufferSize);
				} catch (ArrayIndexOutOfBoundsException e) {
					System.out.println("!");
				}
				Arrays.fill(streamBuffer, (byte) 0);
			}
			// Encode what's left from last stream buffer
			String encodedString = Base64.getEncoder().encodeToString(clipboardBuffer);
			base64StringWriter.append(encodedString);
		} catch (IOException e) {
			e.printStackTrace();
		}
		// Write Base64 encoded data into file, decode with: Certutil -decode file.b64 file.zip
		String urlStreamBase64Encoded = base64StringWriter.toString();
		try (FileWriter writer = new FileWriter(fileName + ".b64", false);
				BufferedWriter bufferedWriter = new BufferedWriter(writer)) {
			bufferedWriter.write(urlStreamBase64Encoded);
		}
		return urlStreamBase64Encoded;
	}

}
