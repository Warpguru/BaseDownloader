package edu.java.rest;

import java.io.File;
import java.net.URL;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.resource.spi.AuthenticationMechanism;
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

import edu.java.service.AuthService;
import edu.java.service.StreamDownloadService;

/**
 * JAX-RS controller for the Base64-download endpoint ({@code GET /api/base}).
 * <p>
 * This controller is intentionally thin: all authentication logic is delegated to {@link AuthService} and all stream-download /
 * Base64-encoding logic is delegated to {@link StreamDownloadService}. The controller itself contains only HTTP glue code.
 * </p>
 *
 * @see AuthenticationMechanism
 */
@Tag(name = "Download WebServices", description = "Maven build info WebServices.")
@Path(ApiConstants.RESOURCE_API_BASE)
@Stateless
public class DownloadController {

    // Sample download urls:
    // https://repo1.maven.org/maven2/com/github/javadev/qrcode-generator/1.1/qrcode-generator-1.1.jar
    // https://repo1.maven.org/maven2/log4j/log4j/1.2.17/log4j-1.2.17.zip

    @Inject
    private AuthService authService;

    @Inject
    private StreamDownloadService streamDownloadService;

    //@formatter:off
	@Operation(
		summary = "Download WebService", 
		description = "Download a resource Base64 encoded WebService.")
	@APIResponses(
		value = {
		    @APIResponse(
		        responseCode = "200", 
		            description = "Build details successfully retrieved in JSON or text format.",
		            content = {
		                @Content(
		                	mediaType = MediaType.TEXT_HTML, 
		                	schema = @Schema(implementation = String.class))
		            }),
		    @APIResponse(
	            responseCode = "401", 
	                description = "Unauthorized, no valid Basic or Bearer (API-key) authentication was provided.",
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
		@SecurityRequirement(name = "BasicAuthentication"),
		@SecurityRequirement(name = "BearerAuthentication")})
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
						@ExampleObject(name = "Sample zipfile download", value = "https://repo1.maven.org/maven2/com/github/javadev/qrcode-generator/1.1/qrcode-generator-1.1.jar"),
						@ExampleObject(name = "Process Explorer zipfile download", value = "https://download.sysinternals.com/files/ProcessExplorer.zip"),
						@ExampleObject(name = "Unzip exe download", value = "https://download.informer.com/win-1193253362-2ecfd01d-62ac9ff1-8e4fcae4627b572817-b2d9117af2b0bcdf2-937370509-1191930848/unzipme.exe")
						}, 
					schema = @org.eclipse.microprofile.openapi.annotations.media.Schema(implementation = String.class)) 
				@QueryParam("url") final String url) {
		//@formatter:on

        // Validate user authentication — delegated to AuthService
        Response authResponse = authService.enforceAuth(authString, apikey);
        if (authResponse != null) {
            return authResponse;
        }

        try {
            final URL urlOfResource = new URL(url);
            final String fileName = new File(urlOfResource.getPath()).getName();
            // Download and Base64-encode — delegated to StreamDownloadService
            final String resourceBase64Encoded = streamDownloadService.downloadStream(urlOfResource, fileName);
            //@formatter:off
            final String htmlContainer = "<!DOCTYPE html>" +
                    "<html>" +
                    "<head>" +
                    "<title>" + fileName + "</title>" +
                    "</head>" +
                    "<body>" +
                    "<div style=\"max-width:100%; word-wrap:break-word;\">" +
                        resourceBase64Encoded +
                    "</div>" +
                    "</body>" +
                    "</html>";
			return Response
				.ok(htmlContainer)
				.build();
			//@formatter:on
        } catch (Exception e) {
            e.printStackTrace();
            //@formatter:off
			return Response
				.status(Status.INTERNAL_SERVER_ERROR)
				.build();
			//@formatter:on
        }
    }

}
