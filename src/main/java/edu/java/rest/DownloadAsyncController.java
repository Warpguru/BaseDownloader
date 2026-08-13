package edu.java.rest;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

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

import edu.java.application.Constants;
import edu.java.service.ChunkedDownloadService;
import edu.java.service.DownloadChunk;
import edu.java.service.DownloadTask;
import edu.java.service.DownloadTaskRegistry;
import edu.java.service.HtmlService;

/**
 * JAX-RS controller for the asynchronous chunked download workflow.
 * <p>
 * Mapped to {@code /api/download}, this controller provides:
 * </p>
 * <ul>
 * <li>{@link #showSubmitForm} &mdash; {@code GET /api/download} &mdash; HTML form for URL submission.</li>
 * <li>{@link #submitDownload} &mdash; {@code POST /api/download} &mdash; validates, registers, and fires the async download;
 * returns HTTP 202 with the task UUID.</li>
 * <li>{@link #getDownloadStatus} &mdash; {@code GET /api/download/{uuid}} &mdash; status page with chunk links and
 * reassembly instructions once the download is complete.</li>
 * <li>{@link #getDownloadChunk} &mdash; {@code GET /api/download/{uuid}/{index}} &mdash; single chunk as a downloadable
 * {@code text/plain} file.</li>
 * <li>{@link #listDownloads} &mdash; {@code GET /api/download/list} &mdash; overview of all active tasks.</li>
 * </ul>
 * <p>
 * The GET &rarr; POST split exists because URLs can exceed typical query-string length limits (2&nbsp;000&ndash;8&nbsp;192
 * characters). Submitting via a {@code <textarea>} in a POST body bypasses those limits entirely.
 * </p>
 * <p>
 * Authentication is enforced by the JAX-RS {@code AuthFilter} before any method is invoked; no
 * per-method credential check is needed. HTML page chrome is provided by {@link HtmlService}.
 * </p>
 * <p>
 * {@code @Stateless} is used (not {@code @Singleton}) so the EJB container can serve concurrent requests
 * from a pool of instances without serialising access via the default write-lock that {@code @Singleton}
 * would impose. This controller holds no mutable instance state.
 * </p>
 */
@Tag(name = "Async Download WebServices", description = "Asynchronous chunked Base64 download WebServices.")
@Path(ApiConstants.RESOURCE_API_DOWNLOAD)
@Stateless
public class DownloadAsyncController {

    @Inject
    private DownloadTaskRegistry registry;

    @Inject
    private ChunkedDownloadService chunkedDownloadService;

    @Inject
    private edu.java.service.ChunkStorageService chunkStorageService;

    @Inject
    private HtmlService htmlService;

    // -------------------------------------------------------------------------
    // GET /api/download — submit form
    // -------------------------------------------------------------------------

    /**
     * Returns an HTML form that lets a browser user submit a download URL.
     *
     * @return 200 OK with a {@code text/html} submit form
     */
    //@formatter:off
	@Operation(
		summary = "Download submit form",
		description = "Returns an HTML form for submitting a download URL. Authentication is handled by "
				+ "the AuthFilter before this method is reached.")
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
        final String action = Constants.CONTEXT_ROOT + "/" + Constants.API_BASE
                + "/" + ApiConstants.RESOURCE_API_DOWNLOAD;
        final String listLink = action + "/list";
        final String body = "<h2>Submit Download</h2>"
                + "<p>Paste a <code>http://</code>, <code>https://</code>, or <code>ftp://</code> URL "
                + "and click <em>Download</em> to start a chunked Base64 download in the background.</p>"
                + "<form method=\"POST\" action=\"" + action + "\">"
                + "<table>"
                + "<tr><td><label for=\"url\">URL:</label></td>"
                + "<td><textarea id=\"url\" name=\"url\" rows=\"4\" cols=\"70\"></textarea></td></tr>"
                + "<tr><td></td><td><input type=\"submit\" value=\"Download\" /></td></tr>"
                + "</table>"
                + "</form>"
                + "<p><a href=\"" + listLink + "\">View all active downloads &rsaquo;</a></p>";
        return Response.ok(htmlService.page("Submit Download", body)).build();
    }

    // -------------------------------------------------------------------------
    // POST /api/download — accept URL, start async download
    // -------------------------------------------------------------------------

    /**
     * Validates the submitted URL, registers a new {@link DownloadTask}, fires the asynchronous download,
     * and returns HTTP 202 Accepted.
     * <p>
     * Authentication is enforced by the JAX-RS {@code AuthFilter} before this method is invoked.
     * </p>
     *
     * @param apikey optional API key form parameter &mdash; threaded into status links; not used for auth here
     * @param url    the {@code http://}, {@code https://}, or {@code ftp://} URL to download
     * @return 202 Accepted with UUID and status link, 400 for invalid input
     */
    //@formatter:off
	@Operation(
		summary = "Submit download",
		description = "Validates the submitted URL, registers a new asynchronous download task, and returns HTTP 202 "
				+ "with the task UUID and a link to the status page.")
	@APIResponses(value = {
		@APIResponse(
			responseCode = "202",
			description = "Download task accepted and started.",
			content = @Content(mediaType = MediaType.TEXT_HTML, schema = @Schema(implementation = String.class))),
		@APIResponse(
			responseCode = "400",
			description = "Bad request — URL is blank or not a valid http/https/ftp URL.",
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
            @Parameter(name = "Authorization", description = "Optional Basic or Bearer Authorization header", in = ParameterIn.HEADER, required = false, hidden = true, schema = @Schema(implementation = String.class)) @HeaderParam("Authorization") final String authString,
            @Parameter(name = "apikey", description = "API key (threaded into status links)", in = ParameterIn.QUERY, required = false, schema = @Schema(implementation = String.class)) @FormParam("apikey") final String apikey,
            @Parameter(name = "url", description = "The http://, https://, or ftp:// URL of the resource to download", in = ParameterIn.QUERY, required = true, schema = @Schema(implementation = String.class)) @FormParam("url") final String url) {

        if (url == null || url.trim().isEmpty()) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(htmlService.errorPage(400, "URL must not be blank."))
                    .build();
        }

        final URL parsedUrl;
        try {
            parsedUrl = new URL(url.trim());
        } catch (MalformedURLException e) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(htmlService.errorPage(400, "Not a valid URL: " + e.getMessage()))
                    .build();
        }
        final String protocol = parsedUrl.getProtocol();
        if (!"http".equals(protocol) && !"https".equals(protocol) && !"ftp".equals(protocol)) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(htmlService.errorPage(400,
                            "Unsupported protocol '" + protocol + "'. Only http, https, and ftp are accepted."))
                    .build();
        }

        final String fileName = new File(parsedUrl.getPath()).getName();
        final DownloadTask task = new DownloadTask(url.trim(), fileName);
        registry.register(task);
        chunkedDownloadService.startDownload(task);

        final String uuid = task.getUuid();
        final String statusLink = Constants.CONTEXT_ROOT + "/" + Constants.API_BASE
                + "/" + ApiConstants.RESOURCE_API_DOWNLOAD + "/" + uuid;

        final List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"URL", HtmlService.esc(url.trim())});
        rows.add(new String[]{"UUID", HtmlService.esc(uuid)});
        rows.add(new String[]{"Status", "<a href=\"" + statusLink + "\">" + statusLink + "</a>"});

        final String body = "<h2>Download Submitted</h2>"
                + htmlService.table(new String[]{"Field", "Value"}, rows)
                + "<p>Reload the status page to check progress.</p>";

        return Response.accepted(htmlService.page("Download Submitted", body))
                .header(ApiConstants.HEADER_X_BD_UUID, uuid)
                .build();
    }

    // -------------------------------------------------------------------------
    // GET /api/download/{uuid} — status page
    // -------------------------------------------------------------------------

    /**
     * Returns an HTML status page for the download task identified by {@code uuid}.
     * <p>
     * Authentication is enforced by the JAX-RS {@code AuthFilter} before this method is invoked.
     * </p>
     * <ul>
     * <li>{@code PENDING} / {@code IN_PROGRESS} &mdash; progress notice; page auto-refreshes every 5 s.</li>
     * <li>{@code DONE} &mdash; chunk table with checksums, reassembly instructions, copy buttons.</li>
     * <li>{@code FAILED} &mdash; error message.</li>
     * </ul>
     *
     * @param uuid       UUID of the download task
     * @param authString optional {@code Authorization} header value &mdash; used as fallback for chunk link suffix
     * @param apikey     optional API key query parameter &mdash; threaded into chunk links
     * @return 200 OK with status page, 404 if UUID not found
     */
    //@formatter:off
	@Operation(
		summary = "Download status",
		description = "Returns an HTML page showing the current status of a download task.")
	@APIResponses(value = {
		@APIResponse(
			responseCode = "200",
			description = "Status page returned successfully.",
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
            @Parameter(name = "uuid", description = "UUID of the download task", in = ParameterIn.PATH, required = true, schema = @Schema(implementation = String.class)) @PathParam("uuid") final String uuid,
            @Parameter(name = "Authorization", description = "Optional Basic or Bearer Authorization header", in = ParameterIn.HEADER, required = false, hidden = true, schema = @Schema(implementation = String.class)) @HeaderParam("Authorization") final String authString,
            @Parameter(name = "apikey", description = "API key (alternative to Authorization header)", in = ParameterIn.QUERY, required = false, schema = @Schema(implementation = String.class)) @QueryParam("apikey") final String apikey) {

        final DownloadTask task = registry.retrieve(uuid);
        if (task == null) {
            return Response.status(Status.NOT_FOUND)
                    .entity(htmlService.errorPage(404, "No download task with UUID: " + uuid))
                    .build();
        }

        final String name = task.getOriginalFileName();
        final DownloadTask.Status status = task.getStatus();
        final int chunkCount = task.getNumberOfChunks();
        final String chunkDir = chunkStorageService.getTaskDirectory(uuid).toString();

        // ── Credential suffix for chunk links ────────────────────────────────
        String credSuffix = "";
        if (apikey != null) {
            credSuffix = "?apikey=" + apikey;
        } else if (authString != null) {
            try {
                credSuffix = "?apikey=" + URLEncoder.encode(authString, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                // UTF-8 always supported
            }
        }

        // ── Extra <head> content ─────────────────────────────────────────────
        final StringBuilder extraHead = new StringBuilder();
        if (status == DownloadTask.Status.PENDING || status == DownloadTask.Status.IN_PROGRESS) {
            // Auto-refresh every 5 seconds — no JavaScript needed
            extraHead.append("<meta http-equiv=\"refresh\" content=\"5\">");
        }
        if (status == DownloadTask.Status.DONE) {
            // Minimal JS: openLinks() opens all chunk tabs; copyCmd(el) copies data-cmd to clipboard
            extraHead.append("<script>")
                .append("function openLinks(){")
                .append("var links=document.querySelectorAll('a.chunk-link');")
                .append("for(var i=0;i<links.length;i++){window.open(links[i].href,'_blank');}")
                .append("}")
                .append("function copyCmd(el){")
                .append("navigator.clipboard.writeText(el.dataset.cmd).then(function(){")
                .append("var prev=el.textContent;el.textContent='\\u2713';")
                .append("setTimeout(function(){el.textContent=prev;},1200);")
                .append("});}")
                .append("</script>");
        }

        // ── Details table ────────────────────────────────────────────────────
        final List<String[]> details = new ArrayList<>();
        details.add(new String[]{"UUID", HtmlService.esc(uuid)});
        details.add(new String[]{"URL", HtmlService.esc(task.getRequestedUrl())});
        details.add(new String[]{"File", HtmlService.esc(name)});
        details.add(new String[]{"Submitted", HtmlService.esc(task.getSubmittedAt().toString())});
        details.add(new String[]{"Expires", HtmlService.esc(task.getExpiresAt().toString())});
        details.add(new String[]{"Status", htmlService.statusBadge(status)});
        final String cdCmd = "cd " + chunkDir;
        details.add(new String[]{"Chunk&nbsp;directory",
                "<code>" + HtmlService.esc(chunkDir) + "</code>"
                + "&thinsp;<button class=\"btn-copy\" data-cmd=\"" + HtmlService.escAttr(cdCmd) + "\""
                + " onclick=\"copyCmd(this)\" title=\"" + HtmlService.escAttr(cdCmd) + "\">&#x229e;</button>"});

        final StringBuilder body = new StringBuilder();
        body.append("<h2>Download Status</h2>");
        body.append(htmlService.table(new String[]{"Field", "Value"}, details));

        // ── Status-specific section ──────────────────────────────────────────
        if (status == DownloadTask.Status.PENDING || status == DownloadTask.Status.IN_PROGRESS) {
            body.append("<p>&#9203; Download is running &mdash; ")
                .append(chunkCount).append(" chunk(s) so far. ")
                .append("This page refreshes automatically every 5 seconds.</p>");

        } else if (status == DownloadTask.Status.DONE) {

            // Chunk table
            body.append("<h3>Chunks</h3>");
            body.append("<p><button onclick=\"openLinks()\">Open all chunks in new tabs</button>"
                    + " <small>(browsers may block pop-ups)</small></p>");

            final List<String[]> chunkRows = new ArrayList<>();
            final String btnStyle = "class=\"btn-copy\"";
            for (int i = 1; i <= chunkCount; i++) {
                final String chunkLink = Constants.CONTEXT_ROOT + "/" + Constants.API_BASE
                        + "/" + ApiConstants.RESOURCE_API_DOWNLOAD + "/" + uuid + "/" + i + credSuffix;
                final String chunkFile = name + "." + i + Constants.CHUNK_FILE_EXTENSION;
                final DownloadChunk chunk = task.getDownloadChunk(i - 1);

                // SHA-256 cell with copy buttons
                String sha256Cell = "";
                String md5Cell = "";
                String crc32Cell = "";
                if (chunk != null) {
                    final String sha256Win = "certutil -hashfile " + chunkFile + " SHA256";
                    final String sha256Lin = "sha256sum " + chunkFile;
                    sha256Cell = "<code>" + chunk.getSha256Hex() + "</code>"
                            + "&thinsp;<button " + btnStyle + " data-cmd=\"" + HtmlService.escAttr(sha256Win) + "\""
                            + " onclick=\"copyCmd(this)\" title=\"certutil\">&#x229e;</button>"
                            + "<button " + btnStyle + " data-cmd=\"" + HtmlService.escAttr(sha256Lin) + "\""
                            + " onclick=\"copyCmd(this)\" title=\"sha256sum\">&#x1f427;</button>";

                    final String md5Win = "certutil -hashfile " + chunkFile + " MD5";
                    final String md5Lin = "md5sum " + chunkFile;
                    md5Cell = "<code>" + chunk.getMd5Hex() + "</code>"
                            + "&thinsp;<button " + btnStyle + " data-cmd=\"" + HtmlService.escAttr(md5Win) + "\""
                            + " onclick=\"copyCmd(this)\" title=\"certutil MD5\">&#x229e;</button>"
                            + "<button " + btnStyle + " data-cmd=\"" + HtmlService.escAttr(md5Lin) + "\""
                            + " onclick=\"copyCmd(this)\" title=\"md5sum\">&#x1f427;</button>";

                    final String crc32Lin = "cksum " + chunkFile;
                    crc32Cell = "<code>" + chunk.getCrc32Hex() + "</code>"
                            + "&thinsp;<button " + btnStyle + " data-cmd=\"" + HtmlService.escAttr(crc32Lin) + "\""
                            + " onclick=\"copyCmd(this)\" title=\"cksum\">&#x1f427;</button>";
                }

                chunkRows.add(new String[]{
                        String.valueOf(i),
                        "<a class=\"chunk-link\" href=\"" + chunkLink + "\">" + HtmlService.esc(chunkFile) + "</a>",
                        sha256Cell,
                        md5Cell,
                        crc32Cell
                });
            }
            body.append(htmlService.table(
                    new String[]{"#", "Filename", "SHA-256", "MD5", "CRC32"},
                    chunkRows));

            // Reassembly — Windows
            body.append("<h3>Reassembly &mdash; Windows</h3>");
            final StringBuilder copyCmd = new StringBuilder("copy /b ");
            for (int i = 1; i <= chunkCount; i++) {
                if (i > 1) copyCmd.append(" + ");
                copyCmd.append(name).append(".").append(i).append(Constants.CHUNK_FILE_EXTENSION);
            }
            copyCmd.append(" ").append(name).append(Constants.CHUNK_FILE_EXTENSION);
            final String certutilCmd = "certutil -decode " + name + Constants.CHUNK_FILE_EXTENSION + " " + name;
            appendCmdRow(body, copyCmd.toString(), "&#x229e;");
            appendCmdRow(body, certutilCmd, "&#x229e;");

            // Reassembly — Linux / macOS
            body.append("<h3>Reassembly &mdash; Linux / macOS</h3>");
            final StringBuilder catCmd = new StringBuilder("cat ");
            for (int i = 1; i <= chunkCount; i++) {
                if (i > 1) catCmd.append(" ");
                catCmd.append(name).append(".").append(i).append(Constants.CHUNK_FILE_EXTENSION);
            }
            catCmd.append(" > ").append(name).append(Constants.CHUNK_FILE_EXTENSION);
            final String base64Cmd = "base64 -d " + name + Constants.CHUNK_FILE_EXTENSION + " > " + name;
            appendCmdRow(body, catCmd.toString(), "&#x1f427;");
            appendCmdRow(body, base64Cmd, "&#x1f427;");

        } else if (status == DownloadTask.Status.FAILED) {
            body.append("<div class=\"error-box\">&#10060; Download failed: ")
                .append(HtmlService.esc(task.getErrorMessage())).append("</div>");
        }

        return Response.ok(htmlService.page("Download Status \u2014 " + uuid,
                body.toString(), extraHead.toString())).build();
    }

    // -------------------------------------------------------------------------
    // GET /api/download/{uuid}/{index} — single chunk
    // -------------------------------------------------------------------------

    /**
     * Returns the Base64-encoded content of one chunk as a downloadable {@code text/plain} file.
     * <p>
     * Authentication is enforced by the JAX-RS {@code AuthFilter} before this method is invoked.
     * The {@code index} parameter is 1-based. A 404 is returned if the index is out of range or the
     * chunk has not yet been produced.
     * </p>
     *
     * @param uuid  UUID of the download task
     * @param index 1-based chunk index
     * @return 200 OK with Base64 text as attachment, 404 if task or chunk not found
     */
    //@formatter:off
	@Operation(
		summary = "Download chunk",
		description = "Returns the Base64-encoded content of a single chunk as a downloadable text/plain file.")
	@APIResponses(value = {
		@APIResponse(
			responseCode = "200",
			description = "Chunk content returned as a downloadable text/plain attachment.",
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
    public Response getDownloadChunk(
            @Parameter(name = "uuid", description = "UUID of the download task", in = ParameterIn.PATH, required = true, schema = @Schema(implementation = String.class)) @PathParam("uuid") final String uuid,
            @Parameter(name = "index", description = "1-based chunk index", in = ParameterIn.PATH, required = true, schema = @Schema(implementation = Integer.class)) @PathParam("index") final int index,
            @Parameter(name = "Authorization", description = "Optional Basic or Bearer Authorization header", in = ParameterIn.HEADER, required = false, hidden = true, schema = @Schema(implementation = String.class)) @HeaderParam("Authorization") final String authString,
            @Parameter(name = "apikey", description = "API key (alternative to Authorization header)", in = ParameterIn.QUERY, required = false, schema = @Schema(implementation = String.class)) @QueryParam("apikey") final String apikey) {

        if (index < 1) {
            return Response.status(Status.NOT_FOUND)
                    .entity("404 Not Found &mdash; chunk index must be >= 1.")
                    .build();
        }

        final DownloadTask task = registry.retrieve(uuid);
        if (task == null) {
            return Response.status(Status.NOT_FOUND)
                    .entity("404 Not Found &mdash; no download task with UUID: " + uuid)
                    .build();
        }

        final DownloadChunk downloadChunk = task.getDownloadChunk(index - 1);
        if (downloadChunk == null) {
            return Response.status(Status.NOT_FOUND)
                    .entity("404 Not Found &mdash; chunk " + index + " is not yet available for UUID: " + uuid)
                    .build();
        }

        final String downloadChunkBase64Content;
        try {
            downloadChunkBase64Content = downloadChunk.getBase64Content();
        } catch (java.io.IOException e) {
            e.printStackTrace();
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                    .entity("500 Internal Server Error &mdash; chunk file could not be read.")
                    .build();
        }

        final String filename = task.getOriginalFileName() + "." + index + Constants.CHUNK_FILE_EXTENSION;
        return Response.ok(downloadChunkBase64Content)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header(ApiConstants.HEADER_X_BD_CRC32, downloadChunk.getCrc32Hex())
                .header(ApiConstants.HEADER_X_BD_MD5, downloadChunk.getMd5Hex())
                .header(ApiConstants.HEADER_X_BD_SHA256, downloadChunk.getSha256Hex())
                .build();
    }

    // -------------------------------------------------------------------------
    // GET /api/download/list — task list
    // -------------------------------------------------------------------------

    /**
     * Returns an HTML overview page listing all download tasks currently in the registry.
     * <p>
     * Authentication is enforced by the JAX-RS {@code AuthFilter} before this method is invoked.
     * The credential passed to this page ({@code apikey} or {@code Authorization} header) is
     * threaded into every status link so the user can navigate without re-entering credentials.
     * </p>
     *
     * @param authString optional {@code Authorization} header &mdash; fallback for link credential suffix
     * @param apikey     optional API key &mdash; threaded into status links
     * @return 200 OK with an HTML task-list page
     */
    //@formatter:off
	@Operation(
		summary = "List all download tasks",
		description = "Returns an HTML overview of all download tasks in the registry.")
	@APIResponses(value = {
		@APIResponse(
			responseCode = "200",
			description = "Task list page returned successfully.",
			content = @Content(mediaType = MediaType.TEXT_HTML, schema = @Schema(implementation = String.class)))
	})
	@SecurityRequirements(value = {
		@SecurityRequirement(name = "BasicAuthentication"),
		@SecurityRequirement(name = "BearerAuthentication")})
	//@formatter:on
    @GET
    @Path("list")
    @Produces(MediaType.TEXT_HTML)
    public Response listDownloads(
            @Parameter(name = "Authorization", description = "Optional Basic or Bearer Authorization header", in = ParameterIn.HEADER, required = false, hidden = true, schema = @Schema(implementation = String.class)) @HeaderParam("Authorization") final String authString,
            @Parameter(name = "apikey", description = "API key (alternative to Authorization header)", in = ParameterIn.QUERY, required = false, schema = @Schema(implementation = String.class)) @QueryParam("apikey") final String apikey) {

        String credSuffix = "";
        if (apikey != null) {
            credSuffix = "?apikey=" + apikey;
        } else if (authString != null) {
            try {
                credSuffix = "?apikey=" + java.net.URLEncoder.encode(authString, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                // UTF-8 always supported
            }
        }

        final java.util.Collection<DownloadTask> tasks = registry.retrieveAll();
        final StringBuilder body = new StringBuilder();
        body.append("<h2>Active Download Tasks</h2>");
        body.append("<p><a href=\"" + Constants.CONTEXT_ROOT + "/" + Constants.API_BASE
                + "/" + ApiConstants.RESOURCE_API_DOWNLOAD + "\">&larr; New download</a></p>");

        if (tasks.isEmpty()) {
            body.append("<p>No download tasks registered.</p>");
        } else {
            final List<String[]> rows = new ArrayList<>();
            for (final DownloadTask task : tasks) {
                final String statusLink = Constants.CONTEXT_ROOT + "/" + Constants.API_BASE
                        + "/" + ApiConstants.RESOURCE_API_DOWNLOAD + "/" + task.getUuid() + credSuffix;
                final int available = task.getNumberOfChunks();
                final int total = task.getTotalChunks();
                rows.add(new String[]{
                        "<a href=\"" + statusLink + "\">" + HtmlService.esc(task.getUuid()) + "</a>",
                        HtmlService.esc(task.getOriginalFileName()),
                        htmlService.statusBadge(task.getStatus()),
                        available + (total >= 0 ? " / " + total : " / ?"),
                        HtmlService.esc(task.getSubmittedAt().toString()),
                        HtmlService.esc(task.getExpiresAt().toString())
                });
            }
            body.append(htmlService.table(
                    new String[]{"UUID", "File", "Status", "Chunks", "Submitted", "Expires"},
                    rows));
        }

        return Response.ok(htmlService.page("Active Downloads", body.toString())).build();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Appends a flex-row with a {@code <pre>} command and a copy button to {@code sb}.
     *
     * @param sb      the StringBuilder to append to
     * @param cmd     the shell command to display and copy
     * @param btnIcon HTML entity for the button label (e.g. {@code &#x229e;} or {@code &#x1f427;})
     */
    private static void appendCmdRow(final StringBuilder sb, final String cmd, final String btnIcon) {
        sb.append("<div class=\"flex-row\">")
          .append("<pre>").append(HtmlService.esc(cmd)).append("</pre>")
          .append("<button class=\"btn-copy\" data-cmd=\"").append(HtmlService.escAttr(cmd)).append("\"")
          .append(" onclick=\"copyCmd(this)\" title=\"").append(HtmlService.escAttr(cmd)).append("\">")
          .append(btnIcon).append("</button>")
          .append("</div>");
    }

}
