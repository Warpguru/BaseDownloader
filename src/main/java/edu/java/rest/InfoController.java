package edu.java.rest;

import javax.ejb.Singleton;
import javax.resource.spi.AuthenticationMechanism;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SAP for build info.
 * 
 * @see AuthenticationMechanism
 */
@Tag(name = "Info WebServices", description = "Maven build info WebServices.")
@Path(ApiConstants.RESOURCE_API_INFO)
@Singleton
public class InfoController {

	private static Logger logger = LoggerFactory.getLogger(InfoController.class);

	//@formatter:off
	@Operation(
		summary = "Test WebService", 
		description = "Show that runtime environment is working.")
	@APIResponses(
		value = {
		    @APIResponse(
		        responseCode = "200", 
		            description = "Build details successfully retrieved in JSON or text format.",
		            content = {
		                @Content(
		                mediaType = MediaType.TEXT_PLAIN, 
		                schema = @Schema(implementation = String.class))
		            })})
	//@formatter:on
	@GET
	@Counted(name = "STS_Counted_InfoController_Info", displayName = "InfoController", description = "Info API counter.", absolute = true, unit = MetricUnits.NONE)
	public Response info(
			@Parameter(description = "UriInfo context injected", schema = @Schema(implementation = UriInfo.class)) @Context UriInfo uriInfo) {
		logger.info("GET {}", uriInfo.getRequestUri());
	//@formatter:off
	return Response.ok("OK")
//	.header(ApiConstants.HEADER_X_HOSTNAME, "AppUtil.getInstance().getHostName()")
	.build();
	//@formatter:on
	}

}
