package edu.java.rest;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirements;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import edu.java.service.AuthService;
import edu.java.service.ChunkedDownloadService;
import edu.java.service.DownloadTask;
import edu.java.service.DownloadTaskRegistry;

/**
 * JAX-RS controller for the asynchronous chunked download workflow.
 * <p>
 * Mapped to {@code /api/download}, this controller provides:
 * </p>
 * <ul>
 *   <li>{@link #showSubmitForm} — {@code GET /api/download} — HTML form for URL submission.</li>
 *   <li>{@link #submitDownload} — {@code POST /api/download} — validates, registers, and fires
 *       the async download; returns HTTP 202 with the task UUID.</li>
 *   <li>{@link #getDownloadStatus} — {@code GET /api/download/{uuid}} — status page with chunk
 *       links and reassembly instructions once the download is complete.</li>
 * </ul>
 * <p>
 * The GET → POST split exists because URLs can exceed typical query-string length limits
 * (2 000–8 192 characters).  Submitting via a {@code <textarea>} in a POST body bypasses those
 * limits entirely.
 * </p>
 * <p>
 * {@code @Stateless} is used (not {@code @Singleton}) so the EJB container can serve concurrent
 * requests from a pool of instances without serialising access via the default write-lock that
 * {@code @Singleton} would impose.  This controller holds no mutable instance state.
 * </p>
 */
@Tag(name = "Async Download WebServices", description = "Asynchronous chunked Base64 download WebServices.")
@Path(ApiConstants.RESOURCE_API_DOWNLOAD)
@Stateless
public class DownloadAsyncController {

	@Inject
	private AuthService authService;

	@Inject
	private DownloadTaskRegistry registry;

	@Inject
	private ChunkedDownloadService chunkedDownloadService;

	/**
	 * Returns an HTML form that lets a browser user submit a download URL without a separate
	 * HTTP client.
	 * <p>
	 * No authentication is required to view the form; credentials are validated at submit time
	 * by {@link #submitDownload}.
	 * </p>
	 *
	 * @return 200 OK with a {@code text/html} submit form
	 */
	//@formatter:off
	@Operation(
		summary = "Download submit form",
		description = "Returns an HTML form for submitting a download URL. No authentication is required to view the form; "
				+ "credentials are validated at submit time (POST).")
	@APIResponses(value = {
		@APIResponse(
			responseCode = "200",
			description = "HTML submit form returned successfully.",
			content = @Content(mediaType = MediaType.TEXT_HTML, schema = @Schema(implementation = String.class)))
	})
	//@formatter:on
	@GET
	@Produces(MediaType.TEXT_HTML)
	public Response showSubmitForm() {
		final String html = "<!DOCTYPE html>"
				+ "<html><head><title>BaseDownloader — Submit</title></head>"
				+ "<body>"
				+ "<h2>BaseDownloader</h2>"
				+ "<p>Paste the <code>http://</code>, <code>https://</code>, or <code>ftp://</code> URL of the file "
				+ "you want to download, enter your API key, then click <em>Download</em>.</p>"
				+ "<form method=\"POST\" action=\"/base-downloader/api/download\">"
				+ "<table>"
				+ "<tr><td><label for=\"url\">URL:</label></td>"
				+ "<td><textarea id=\"url\" name=\"url\" rows=\"4\" cols=\"80\"></textarea></td></tr>"
				+ "<tr><td><label for=\"apikey\">API key:</label></td>"
				+ "<td><input id=\"apikey\" name=\"apikey\" type=\"text\" size=\"40\" /></td></tr>"
				+ "<tr><td></td><td><input type=\"submit\" value=\"Download\" /></td></tr>"
				+ "</table>"
				+ "</form>"
				+ "</body></html>";
		return Response.ok(html).build();
	}

	/**
	 * Validates the submitted URL, registers a new {@link DownloadTask}, fires the asynchronous
	 * download via {@link ChunkedDownloadService}, and returns HTTP 202 Accepted.
	 * <p>
	 * The download runs in a background EJB-managed thread; the response is returned immediately
	 * with the task UUID so the caller can poll {@link #getDownloadStatus} for progress.
	 * </p>
	 *
	 * @param authString optional {@code Authorization} header value (Basic or Bearer)
	 * @param apikey     optional API key query/form parameter (alternative to the header)
	 * @param url        the {@code http://}, {@code https://}, or {@code ftp://} URL to download
	 * @return 202 Accepted with UUID and status link, 400 for invalid input, 401 if not authenticated
	 */
	//@formatter:off
	@Operation(
		summary = "Submit download",
		description = "Validates the submitted URL, registers a new asynchronous download task, and returns HTTP 202 "
				+ "with the task UUID and a link to the status page. The download runs in the background; "
				+ "poll the status endpoint until status is DONE.")
	@APIResponses(value = {
		@APIResponse(
			responseCode = "202",
			description = "Download task accepted and started. Response header X-BD-UUID contains the task UUID.",
			content = @Content(mediaType = MediaType.TEXT_HTML, schema = @Schema(implementation = String.class))),
		@APIResponse(
			responseCode = "400",
			description = "Bad request — URL is blank or not a valid http/https/ftp URL.",
			content = @Content(mediaType = MediaType.TEXT_HTML, schema = @Schema(implementation = String.class))),
		@APIResponse(
			responseCode = "401",
			description = "Unauthorized — no valid Basic or Bearer authentication was provided.",
			content = @Content(mediaType = MediaType.TEXT_HTML, schema = @Schema(implementation = String.class))),
		@APIResponse(
			responseCode = "500",
			description = "Internal server error.",
			content = @Content(mediaType = MediaType.TEXT_HTML, schema = @Schema(implementation = String.class)))
	})
	@SecurityRequirements(value = {
		@SecurityRequirement(name = "BasicAuthentication"),
		@SecurityRequirement(name = "BearerAuthentication")})
	//@formatter:on
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.TEXT_HTML)
	public Response submitDownload(
			@Parameter(name = "Authorization", description = "Optional Basic or Bearer Authorization header for programmatic callers",
					in = ParameterIn.HEADER, required = false, hidden = true,
					schema = @Schema(implementation = String.class))
			@HeaderParam("Authorization") final String authString,
			@Parameter(name = "apikey", description = "API key (alternative to Authorization header)",
					in = ParameterIn.QUERY, required = false,
					schema = @Schema(implementation = String.class))
			@FormParam("apikey") final String apikey,
			@Parameter(name = "url", description = "The http://, https://, or ftp:// URL of the resource to download",
					in = ParameterIn.QUERY, required = true,
					schema = @Schema(implementation = String.class))
			@FormParam("url") final String url) {

		// Validate that a URL was supplied
		if (url == null || url.trim().isEmpty()) {
			return Response.status(Status.BAD_REQUEST)
					.entity("<html><body><p>400 Bad Request — URL must not be blank.</p></body></html>")
					.build();
		}

		// Enforce authentication (basic and bearer are equivalent)
		final Response authResponse = authService.enforceAuth(authString, "Bearer " + apikey);
		if (authResponse != null) {
			return authResponse;
		}

		// Validate URL format and protocol
		final URL parsedUrl;
		try {
			parsedUrl = new URL(url.trim());
		} catch (MalformedURLException e) {
			return Response.status(Status.BAD_REQUEST)
					.entity("<html><body><p>400 Bad Request — Not a valid URL: " + e.getMessage() + "</p></body></html>")
					.build();
		}
		final String protocol = parsedUrl.getProtocol();
		if (!"http".equals(protocol) && !"https".equals(protocol) && !"ftp".equals(protocol)) {
			return Response.status(Status.BAD_REQUEST)
					.entity("<html><body><p>400 Bad Request — Unsupported protocol '" + protocol
							+ "'. Only http, https, and ftp are accepted.</p></body></html>")
					.build();
		}

		// Derive filename from URL path
		final String fileName = new File(parsedUrl.getPath()).getName();

		// Register the task and fire the async download
		final DownloadTask task = new DownloadTask(url.trim(), fileName);
		registry.register(task);
		chunkedDownloadService.startDownload(task);

		final String uuid = task.getUuid();
		final String statusLink = "/base-downloader/api/download/" + uuid;
		//@formatter:off
		final String html = "<!DOCTYPE html>"
				+ "<html><head><title>Download submitted</title></head>"
				+ "<body>"
				+ "<h2>Download submitted</h2>"
				+ "<table>"
				+ "<tr><td><strong>URL:</strong></td><td>" + url.trim() + "</td></tr>"
				+ "<tr><td><strong>UUID:</strong></td><td>" + uuid + "</td></tr>"
				+ "<tr><td><strong>Status:</strong></td>"
				+ "<td><a href=\"" + statusLink + "\">" + statusLink + "</a></td></tr>"
				+ "</table>"
				+ "<p>Reload the status page to check progress.</p>"
				+ "</body></html>";
		return Response.accepted(html)
				.header(ApiConstants.HEADER_X_BD_UUID, uuid)
				.build();
		//@formatter:on
	}

	/**
	 * Returns an HTML status page for the download task identified by {@code uuid}.
	 * <p>
	 * The page content varies by {@link DownloadTask.Status}:
	 * </p>
	 * <ul>
	 *   <li>{@code PENDING} / {@code IN_PROGRESS} — progress notice with current chunk count.</li>
	 *   <li>{@code DONE} — numbered list of chunk links, an "Open all chunk tabs" button, and
	 *       reassembly instructions for Windows ({@code certutil}) and Linux/macOS
	 *       ({@code base64 -d}).</li>
	 *   <li>{@code FAILED} — the error message from {@link DownloadTask#getErrorMessage()}.</li>
	 * </ul>
	 *
	 * @param uuid       UUID of the download task
	 * @param authString optional {@code Authorization} header value (Basic or Bearer)
	 * @param apikey     optional API key query parameter (alternative to the header)
	 * @return 200 OK with status page, 401 if not authenticated, 404 if UUID not found
	 */
	//@formatter:off
	@Operation(
		summary = "Download status",
		description = "Returns an HTML page showing the current status of a download task. "
				+ "When DONE, lists all chunk download links and provides reassembly instructions for Windows and Linux/macOS.")
	@APIResponses(value = {
		@APIResponse(
			responseCode = "200",
			description = "Status page returned successfully.",
			content = @Content(mediaType = MediaType.TEXT_HTML, schema = @Schema(implementation = String.class))),
		@APIResponse(
			responseCode = "401",
			description = "Unauthorized — no valid Basic or Bearer authentication was provided.",
			content = @Content(mediaType = MediaType.TEXT_HTML, schema = @Schema(implementation = String.class))),
		@APIResponse(
			responseCode = "404",
			description = "No task found for the given UUID.",
			content = @Content(mediaType = MediaType.TEXT_HTML, schema = @Schema(implementation = String.class)))
	})
	@SecurityRequirements(value = {
		@SecurityRequirement(name = "BasicAuthentication"),
		@SecurityRequirement(name = "BearerAuthentication")})
	//@formatter:on
	@GET
	@Path("{uuid}")
	@Produces(MediaType.TEXT_HTML)
	public Response getDownloadStatus(
			@Parameter(name = "uuid", description = "UUID of the download task",
					in = ParameterIn.PATH, required = true,
					schema = @Schema(implementation = String.class))
			@PathParam("uuid") final String uuid,
			@Parameter(name = "Authorization", description = "Optional Basic or Bearer Authorization header",
					in = ParameterIn.HEADER, required = false, hidden = true,
					schema = @Schema(implementation = String.class))
			@HeaderParam("Authorization") final String authString,
			@Parameter(name = "apikey", description = "API key (alternative to Authorization header)",
					in = ParameterIn.QUERY, required = false,
					schema = @Schema(implementation = String.class))
			@QueryParam("apikey") final String apikey) {

        // Enforce authentication (basic and bearer are equivalent)
		final Response authResponse = authService.enforceAuth(authString, "Bearer " + apikey);
		if (authResponse != null) {
			return authResponse;
		}

		// Look up task
		final DownloadTask task = registry.retrieve(uuid);
		if (task == null) {
			return Response.status(Status.NOT_FOUND)
					.entity("<html><body><p>404 Not Found &mdash; no download task with UUID: " + uuid + "</p></body></html>")
					.build();
		}

		final String name = task.getOriginalFileName();
		final DownloadTask.Status status = task.getStatus();
		final int chunkCount = task.getNumberOfChunks();
		final StringBuilder sb = new StringBuilder();

		sb.append("<!DOCTYPE html><html><head><title>Download Status &mdash; ").append(uuid).append("</title>");

		// Embed openLinks() JS only when DONE (browsers may block pop-ups)
		if (status == DownloadTask.Status.DONE) {
			sb.append("<script>")
			  .append("function openLinks(){")
			  .append("var links=document.getElementsByTagName('a');")
			  .append("for(var i=0;i<links.length;i++){")
			  .append("window.open(links[i].getAttribute('href'),'_blank');")
			  .append("window.focus();")
			  .append("}}")
			  .append("</script>");
		}

		sb.append("</head><body>");
		sb.append("<h2>Download Status</h2>");

		// Request details table
		sb.append("<table>");
		sb.append("<tr><td><strong>UUID:</strong></td><td>").append(uuid).append("</td></tr>");
		sb.append("<tr><td><strong>URL:</strong></td><td>").append(task.getRequestedUrl()).append("</td></tr>");
		sb.append("<tr><td><strong>File:</strong></td><td>").append(name).append("</td></tr>");
		sb.append("<tr><td><strong>Submitted:</strong></td><td>").append(task.getSubmittedAt()).append("</td></tr>");
		sb.append("<tr><td><strong>Expires:</strong></td><td>").append(task.getExpiresAt()).append("</td></tr>");
		sb.append("<tr><td><strong>Status:</strong></td><td>").append(status).append("</td></tr>");
		sb.append("</table>");

		// Status-specific section
		if (status == DownloadTask.Status.PENDING || status == DownloadTask.Status.IN_PROGRESS) {
			sb.append("<p>&#9203; Download is running &mdash; ").append(chunkCount)
			  .append(" chunk(s) produced so far. Reload this page to check progress.</p>");

		} else if (status == DownloadTask.Status.DONE) {
			// Append ?apikey=... to every chunk link so the browser can follow them without
			// re-entering credentials.  Prefer the apikey query param as-is; fall back to the
			// Authorization header value (which authenticate() also accepts in full "Bearer …"
			// or "Basic …" form).  The header value must be URL-encoded because it contains
			// spaces and '=' padding characters.
			String credSuffix = "";
			if (apikey != null) {
				credSuffix = "?apikey=" + apikey;
			} else if (authString != null) {
				try {
					credSuffix = "?apikey=" + URLEncoder.encode(authString, "UTF-8");
				} catch (UnsupportedEncodingException e) {
					// UTF-8 is always supported; never reached
				}
			}

			sb.append("<h3>Chunks</h3>");
			sb.append("<ol>");
			for (int i = 1; i <= chunkCount; i++) {
				final String chunkLink = "/base-downloader/api/download/" + uuid + "/" + i + credSuffix;
				sb.append("<li><a href=\"").append(chunkLink).append("\">")
				  .append(name).append(".").append(i).append(".txt")
				  .append("</a></li>");
			}
			sb.append("</ol>");

			// Open all chunks button — browsers may block the resulting pop-ups
			sb.append("<p><button onclick=\"openLinks()\">Open all chunk tabs</button>")
			  .append(" <small>(browsers may block pop-ups)</small></p>");

			// Reassembly instructions — Windows
			sb.append("<h3>Reassembly &mdash; Windows</h3>");
			final StringBuilder copyCmd = new StringBuilder("copy /b ");
			for (int i = 1; i <= chunkCount; i++) {
				if (i > 1) copyCmd.append(" + ");
				copyCmd.append(name).append(".").append(i).append(".txt");
			}
			copyCmd.append(" ").append(name).append(".txt");
			sb.append("<pre>").append(copyCmd).append("</pre>");
			sb.append("<pre>certutil -decode ").append(name).append(".txt ").append(name).append("</pre>");

			// Reassembly instructions — Linux / macOS
			sb.append("<h3>Reassembly &mdash; Linux / macOS</h3>");
			final StringBuilder catCmd = new StringBuilder("cat ");
			for (int i = 1; i <= chunkCount; i++) {
				if (i > 1) catCmd.append(" ");
				catCmd.append(name).append(".").append(i).append(".txt");
			}
			catCmd.append(" > ").append(name).append(".txt");
			sb.append("<pre>").append(catCmd).append("</pre>");
			sb.append("<pre>base64 -d ").append(name).append(".txt > ").append(name).append("</pre>");

		} else if (status == DownloadTask.Status.FAILED) {
			sb.append("<p>&#10060; Download failed: ").append(task.getErrorMessage()).append("</p>");
		}

		sb.append("</body></html>");
		return Response.ok(sb.toString()).build();
	}

	/**
	 * Returns the Base64-encoded content of one chunk as a downloadable {@code text/plain} file.
	 * <p>
	 * The {@code index} parameter is 1-based (matching the link numbers shown on the status page).
	 * It is converted to a 0-based list index internally.  A 404 is returned if the index is out
	 * of range or the chunk has not yet been produced by the background download.
	 * </p>
	 * <p>
	 * The response includes a {@code Content-Disposition: attachment} header so the browser saves
	 * the chunk as {@code {originalFileName}.{index}.txt} rather than displaying it inline.
	 * </p>
	 *
	 * @param uuid       UUID of the download task
	 * @param index      1-based chunk index
	 * @param authString optional {@code Authorization} header value (Basic or Bearer)
	 * @param apikey     optional API key query parameter (alternative to the header)
	 * @return 200 OK with Base64 text, 401 if not authenticated, 404 if task or chunk not found
	 */
	//@formatter:off
	@Operation(
		summary = "Download chunk",
		description = "Returns the Base64-encoded content of a single chunk as a downloadable text/plain file. "
				+ "The index is 1-based, matching the link numbers on the status page.")
	@APIResponses(value = {
		@APIResponse(
			responseCode = "200",
			description = "Chunk content returned as a downloadable text/plain attachment.",
			content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(implementation = String.class))),
		@APIResponse(
			responseCode = "401",
			description = "Unauthorized &mdash; no valid Basic or Bearer authentication was provided.",
			content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(implementation = String.class))),
		@APIResponse(
			responseCode = "404",
			description = "Task not found, chunk index out of range, or chunk not yet available.",
			content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(implementation = String.class)))
	})
	@SecurityRequirements(value = {
		@SecurityRequirement(name = "BasicAuthentication"),
		@SecurityRequirement(name = "BearerAuthentication")})
	//@formatter:on
	@GET
	@Path("{uuid}/{index}")
	@Produces(MediaType.TEXT_PLAIN)
	public Response getChunk(
			@Parameter(name = "uuid", description = "UUID of the download task",
					in = ParameterIn.PATH, required = true,
					schema = @Schema(implementation = String.class))
			@PathParam("uuid") final String uuid,
			@Parameter(name = "index", description = "1-based chunk index",
					in = ParameterIn.PATH, required = true,
					schema = @Schema(implementation = Integer.class))
			@PathParam("index") final int index,
			@Parameter(name = "Authorization", description = "Optional Basic or Bearer Authorization header",
					in = ParameterIn.HEADER, required = false, hidden = true,
					schema = @Schema(implementation = String.class))
			@HeaderParam("Authorization") final String authString,
			@Parameter(name = "apikey", description = "API key (alternative to Authorization header)",
					in = ParameterIn.QUERY, required = false,
					schema = @Schema(implementation = String.class))
			@QueryParam("apikey") final String apikey) {

        // Enforce authentication (basic and bearer are equivalent)
        final Response authResponse = authService.enforceAuth(authString, "Bearer " + apikey);
		if (authResponse != null) {
			return authResponse;
		}

		// Validate index range (1-based public API)
		if (index < 1) {
			return Response.status(Status.NOT_FOUND)
					.entity("404 Not Found &mdash; chunk index must be >= 1.")
					.build();
		}

		// Look up task
		final DownloadTask task = registry.retrieve(uuid);
		if (task == null) {
			return Response.status(Status.NOT_FOUND)
					.entity("404 Not Found &mdash; no download task with UUID: " + uuid)
					.build();
		}

		// Convert to 0-based and fetch chunk (returns null if not yet available or out of range)
		final String chunk = task.getChunk(index - 1);
		if (chunk == null) {
			return Response.status(Status.NOT_FOUND)
					.entity("404 Not Found &mdash; chunk " + index + " is not yet available for UUID: " + uuid)
					.build();
		}

		final String filename = task.getOriginalFileName() + "." + index + ".txt";
		return Response.ok(chunk)
				.header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
				.build();
	}

}
