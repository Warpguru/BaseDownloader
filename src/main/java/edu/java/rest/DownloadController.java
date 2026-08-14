package edu.java.rest;

import java.io.File;
import java.net.URL;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.core.Response.Status;

import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirements;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.java.security.RequestContext;
import edu.java.service.HtmlService;
import edu.java.service.StreamDownloadService;

/**
 * JAX-RS controller for the legacy single-stream Base64-download endpoint ({@code GET /api/download}).
 * <p>
 * This controller is intentionally thin: authentication is handled by the JAX-RS {@code AuthFilter} before any controller
 * method is invoked; all stream-download / Base64-encoding logic is delegated to {@link StreamDownloadService}; and HTML page
 * chrome is provided by {@link HtmlService}. The controller itself contains only HTTP glue code.
 * </p>
 * <p>
 * {@code @Stateless} is used (not {@code @Singleton}) so the EJB container can serve concurrent requests from a pool of
 * instances without serialising access via the default write-lock that {@code @Singleton} would impose. This controller holds
 * no mutable instance state.
 * </p>
 */
@Tag(name = "Download WebServices", description = "Base64 download WebServices.")
@Path(ApiConstants.RESOURCE_API_DOWNLOAD)
@Stateless
public class DownloadController {

    // Sample download urls:
    // https://repo1.maven.org/maven2/com/github/javadev/qrcode-generator/1.1/qrcode-generator-1.1.jar
    // https://repo1.maven.org/maven2/log4j/log4j/1.2.17/log4j-1.2.17.zip
    // ftp://demo:password@test.rebex.net/pub/example/KeyGenerator.png

    private static final Logger logger = LoggerFactory.getLogger(DownloadController.class);

    @Inject
    private StreamDownloadService streamDownloadService;

    @Inject
    private HtmlService htmlService;

    @Inject
    private RequestContext requestContext;

    //@formatter:off
	@Operation(
		summary = "Download WebService",
		description = "Download a resource Base64 encoded WebService.")
	@APIResponses(
		value = {
		    @APIResponse(
		        responseCode = "200",
		            description = "Resource downloaded and returned Base64-encoded in an HTML container.",
		            content = {
		                @Content(
		                	mediaType = MediaType.TEXT_HTML,
		                	schema = @Schema(implementation = String.class))
		            }),
		    @APIResponse(
	            responseCode = "500",
	                description = "Internal server error while downloading resource Base64 encoded.",
	                content = {
	                    @Content(
	                    		mediaType = MediaType.TEXT_HTML,
	                    		schema = @Schema(implementation = String.class))
	                })
		  })
	@SecurityRequirements(value = {
		@SecurityRequirement(name = ApiConstants.SECURITY_SCHEME_BASIC),
		@SecurityRequirement(name = ApiConstants.SECURITY_SCHEME_BEARER)})
	@GET
	@Produces(MediaType.TEXT_HTML)
	@Counted(name = "BD_Counted_DownloadController_Base64Download", displayName = "DownloadController", description = "Download API counter.", absolute = true, unit = MetricUnits.NONE)
	public Response base64Download(
            @Parameter(hidden = true) @Context final UriInfo uriInfo,
			@Parameter(description = "Url to resource to Base64 encode", in = ParameterIn.QUERY, required = true, allowEmptyValue = false,
					examples = {
						@ExampleObject(name = "QRCode generator (small) zipfile HTTPS download", value = "https://repo1.maven.org/maven2/com/github/javadev/qrcode-generator/1.1/qrcode-generator-1.1.jar"),
						@ExampleObject(name = "Process Explorer (large) zipfile HTTPS download", value = "https://download.sysinternals.com/files/ProcessExplorer.zip"),
						@ExampleObject(name = "Unzip (small) executable HTTPS download", value = "https://download.informer.com/win-1193253362-2ecfd01d-62ac9ff1-8e4fcae4627b572817-b2d9117af2b0bcdf2-937370509-1191930848/unzipme.exe"),
                        @ExampleObject(name = "Sample (small) image FTP download", value = "ftp://demo:password@test.rebex.net/pub/example/KeyGenerator.png")
			        },
					schema = @org.eclipse.microprofile.openapi.annotations.media.Schema(implementation = String.class))
			@QueryParam("url") final String url) {
		//@formatter:on
        logger.info("GET {} url={}", uriInfo.getRequestUri(), url);
        try {
            final URL urlOfResource = new URL(url);
            final String fileName = new File(urlOfResource.getPath()).getName();
            final String resourceBase64Encoded = streamDownloadService.downloadStream(urlOfResource, fileName);
            final String b64FileName = HtmlService.escAttr(fileName + ".b64");

            // Minimal JS injected into <head>:
            //   copyB64()  — copies textarea content to clipboard (no size limit via Clipboard API)
            //   saveB64()  — triggers a real browser file download via Blob + createObjectURL,
            //                which is safe for arbitrarily large content (no data: URI size limit)
            final String extraHead = "<script>"
                    + "function copyB64(){"
                    + "var t=document.getElementById('b64out');"
                    + "navigator.clipboard.writeText(t.value).then(function(){"
                    + "var b=document.getElementById('btn-copy');"
                    + "var prev=b.textContent;b.textContent='\\u2713 Copied!';"
                    + "setTimeout(function(){b.textContent=prev;},1500);"
                    + "});}"
                    + "function saveB64(){"
                    + "var t=document.getElementById('b64out');"
                    + "var blob=new Blob([t.value],{type:'text/plain'});"
                    + "var a=document.createElement('a');"
                    + "a.href=URL.createObjectURL(blob);"
                    + "a.download='" + b64FileName + "';"
                    + "a.click();"
                    + "URL.revokeObjectURL(a.href);}"
                    + "</script>";

            //@formatter:off
            final String body = "<h2>" + HtmlService.esc(fileName) + "</h2>"
                    + "<p>"
                    + "<button id=\"btn-copy\" class=\"btn-copy\" onclick=\"copyB64()\" title=\"Copy Base64 content to clipboard\">&#x2398; Copy to clipboard</button>"
                    + "&thinsp;"
                    + "<button class=\"btn-copy\" onclick=\"saveB64()\" title=\"Download as " + b64FileName + "\">&#x2913; Download " + HtmlService.esc(fileName + ".b64") + "</button>"
                    + "</p>"
                    + "<textarea id=\"b64out\" readonly rows=\"20\""
                    + " style=\"font-family:Consolas,'Courier New',monospace;font-size:12px;"
                    + "width:100%;resize:vertical;word-break:break-all;white-space:pre-wrap;"
                    + "background:#f4f4f4;border:1px solid #ccc;padding:0.5em;border-radius:3px;\">"
                    + resourceBase64Encoded
                    + "</textarea>";
            return Response.ok(htmlService.page(fileName, body, extraHead, requestContext.getUsername()))
                    .build();
            //@formatter:on
        } catch (Exception e) {
            e.printStackTrace();
            //@formatter:off
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                    .entity(htmlService.errorPage(Status.INTERNAL_SERVER_ERROR, "Error downloading resource: " + HtmlService.esc(e.getMessage()), requestContext.getUsername()))
                    .build();
            //@formatter:on
        }
    }

}
