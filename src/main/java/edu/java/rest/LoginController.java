package edu.java.rest;

import javax.ejb.Singleton;
import javax.resource.spi.AuthenticationMechanism;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SAP for login.
 * 
 * @see AuthenticationMechanism
 */
@Tag(name = "Login WebServices", description = "Application login WebServices.")
@Path(ApiConstants.RESOURCE_API_LOGIN)
@Singleton
public class LoginController {

	private static Logger logger = LoggerFactory.getLogger(InfoController.class);
	
	//@formatter:off
	@Operation(
		summary = "Login WebService", 
		description = "Return login screen as HTML.")
	@APIResponses(
		value = {
		    @APIResponse(
		        responseCode = "200", 
		            description = "Return login screen as HTML.",
		            content = {
		                @Content(
		                mediaType = MediaType.TEXT_HTML, 
		                schema = @Schema(implementation = String.class))
		            })})
	//@formatter:on
	@GET
	@Produces(MediaType.TEXT_HTML)
	public Response login(
			@Parameter(description = "ServletContext context injected", schema = @Schema(implementation = ServletContext.class)) final @Context ServletContext servletContext,
			@Parameter(description = "UriInfo context injected", schema = @Schema(implementation = UriInfo.class)) final @Context UriInfo uriInfo) {
		logger.info("GET {}", uriInfo.getRequestUri());
		return Response.ok("<HTML><HEAD></HEAD><BODY>Login</BODY></HTML>")
				.build();
		
	}
	
	//@formatter:off
	@Operation(
		summary = "Login WebService", 
		description = "Retrieve credentials from login screen.")
	@APIResponses(
		value = {
		    @APIResponse(
		        responseCode = "200", 
		            description = "Login successfull.",
		            content = {
		                @Content(
		                mediaType = MediaType.TEXT_HTML, 
		                schema = @Schema(implementation = String.class))
		            })})
	//@formatter:on
	@POST
	public Response login2(
			@Parameter(description = "ServletContext context injected", schema = @Schema(implementation = ServletContext.class)) final @Context ServletContext servletContext,
			@Parameter(description = "UriInfo context injected", schema = @Schema(implementation = UriInfo.class)) final @Context UriInfo uriInfo,
			@Parameter(description = "HttpServletRequest injected", schema = @Schema(implementation = HttpServletRequest.class))final @Context HttpServletRequest httpServletRequest,
			@Parameter(description = "HttpServletResponse injected", schema = @Schema(implementation = HttpServletResponse.class))final @Context HttpServletResponse httpServletResponse,
			@Parameter(description = "Username", in = ParameterIn.QUERY, required = true, allowEmptyValue = false, examples = {
					@ExampleObject(name = "Sample username", value = "username") }, schema = @org.eclipse.microprofile.openapi.annotations.media.Schema(implementation = String.class)) @QueryParam("username") final String username,
			@Parameter(description = "Password", in = ParameterIn.QUERY, required = true, allowEmptyValue = false, examples = {
					@ExampleObject(name = "Sample password", value = "password") }, schema = @org.eclipse.microprofile.openapi.annotations.media.Schema(implementation = String.class)) @QueryParam("password") final String password,
			@Parameter(description = "Userid", in = ParameterIn.QUERY, required = true, allowEmptyValue = false, examples = {
					@ExampleObject(name = "Sample token", value = "token") }, schema = @org.eclipse.microprofile.openapi.annotations.media.Schema(implementation = String.class)) @QueryParam("token") final String token) {
		logger.info("POST {}", uriInfo.getRequestUri());
//		RequestDispatcher requestDispatcher = servletContext.getRequestDispatcher("/login");
//		requestDispatcher.forward(httpServletRequest, httpServletResponse);
		return Response.ok("<HTML><HEAD></HEAD><BODY>You're logged in!</BODY></HTML>")
				.build();
	}
	
}
