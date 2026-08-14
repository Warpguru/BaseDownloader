package edu.java.rest;

import java.io.File;
import java.net.URL;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

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

import edu.java.security.RequestContext;
import edu.java.service.HtmlService;
import edu.java.service.StreamDownloadService;

/**
 * JAX-RS controller for the legacy single-stream Base64-download endpoint ({@code GET /api/base}).
 * <p>
 * This controller is intentionally thin: authentication is handled by the JAX-RS {@code AuthFilter}
 * before any controller method is invoked; all stream-download / Base64-encoding logic is delegated
 * to {@link StreamDownloadService}; and HTML page chrome is provided by {@link HtmlService}.
 * The controller itself contains only HTTP glue code.
 * </p>
 * <p>
 * {@code @Stateless} is used (not {@code @Singleton}) so the EJB container can serve concurrent
 * requests from a pool of instances without serialising access via the default write-lock that
 * {@code @Singleton} would impose. This controller holds no mutable instance state.
 * </p>
 */
@Tag(name = "Download WebServices", description = "Maven build info WebServices.")
@Path(ApiConstants.RESOURCE_API_BASE)
@Stateless
public class DownloadController {

    // Sample download urls:
    // https://repo1.maven.org/maven2/com/github/javadev/qrcode-generator/1.1/qrcode-generator-1.1.jar
    // https://repo1.maven.org/maven2/log4j/log4j/1.2.17/log4j-1.2.17.zip
    // ftp://demo:password@test.rebex.net/pub/example/KeyGenerator.png

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
		            description = "Resource downloaded and returned as Base64-encoded HTML.",
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
	@Counted(name = "STS_Counted_DownloadController_Base64Download", displayName = "DownloadController", description = "Download API counter.", absolute = true, unit = MetricUnits.NONE)
	public Response base64Download(
			@Parameter(description = "Optional Basic or Bearer authorization HTTP header (Base64 encoded)", in = ParameterIn.HEADER, required = false, hidden = true,
					schema = @Schema(implementation = String.class))
				@HeaderParam("Authorization") String authString,
			@Parameter(description = "UriInfo context injected", schema = @Schema(implementation = UriInfo.class)) @Context UriInfo uriInfo,
			@Parameter(description = "API key", schema = @Schema(implementation = String.class)) @QueryParam("apikey") final String apikey,
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

        try {
            final URL urlOfResource = new URL(url);
            final String fileName = new File(urlOfResource.getPath()).getName();
            final String resourceBase64Encoded = streamDownloadService.downloadStream(urlOfResource, fileName);
            final String body = "<h2>" + HtmlService.esc(fileName) + "</h2>"
                    + "<div style=\"max-width:100%;word-wrap:break-word;overflow-wrap:break-word\">"
                    + resourceBase64Encoded
                    + "</div>";
            return Response.ok(htmlService.page(fileName, body, "", requestContext.getUsername())).build();
        } catch (Exception e) {
            e.printStackTrace();
            //@formatter:off
   return Response
    .status(Status.INTERNAL_SERVER_ERROR)
    .entity(htmlService.errorPage(Status.INTERNAL_SERVER_ERROR, "Error downloading resource: " + HtmlService.esc(e.getMessage()), requestContext.getUsername()))
    .build();
   //@formatter:on
        }
    }

}
