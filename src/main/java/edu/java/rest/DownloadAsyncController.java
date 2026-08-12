package edu.java.rest;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
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
 * Mapped to {@code /api/download}, this controller provides two entry points:
 * </p>
 * <ul>
 *   <li>{@link #showSubmitForm} — {@code GET /api/download} — returns an HTML form so a browser
 *       user can paste a URL and submit it without needing a separate client.</li>
 *   <li>{@link #submitDownload} — {@code POST /api/download} — validates the submitted URL,
 *       registers a new {@link DownloadTask}, fires the asynchronous download via
 *       {@link ChunkedDownloadService}, and returns HTTP 202 with the task UUID.</li>
 * </ul>
 * <p>
 * The GET → POST split exists because URLs can exceed typical query-string length limits
 * (2 000–8 192 characters depending on browser and server).  Submitting via a {@code <textarea>}
 * in a POST body bypasses those limits entirely.
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

}
