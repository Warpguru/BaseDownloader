package edu.java.rest;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URL;
import java.util.Arrays;
import java.util.Base64;

import javax.ejb.Singleton;
import javax.json.bind.JsonbException;
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

/**
 * SAP for build info.
 * 
 * @see AuthenticationMechanism
 */
@Tag(name = "Download WebServices", description = "Maven build info WebServices.")
@Path(ApiConstants.RESOURCE_API_BASE)
@Singleton
public class DownloadController {

	private final static int BUFFER_LENGTH_STREAM = 1024;
//	private final static String FILE_URL = "https://repo1.maven.org/maven2/com/github/javadev/qrcode-generator/1.1/qrcode-generator-1.1.jar";
//	private final static String FILE_URL = "https://repo1.maven.org/maven2/log4j/log4j/1.2.17/log4j-1.2.17.zip";
	private final static String FILE_NAME_ZIP = "test.zip";
	private final static String FILE_NAME_BASE64 = "test.b64";

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
//		                	mediaType = MediaType.APPLICATION_OCTET_STREAM, 
		                	mediaType = MediaType.TEXT_HTML, 
		                	schema = @Schema(implementation = String.class))
		            }),
		    @APIResponse(
	            responseCode = "401", 
	                description = "Unauthorized, no valid Basic or Bearer (API-key) authentication was provided.",
	                content = {
	                    @Content(
//   	                    mediaType = MediaType.APPLICATION_OCTET_STREAM, 
	   	                    mediaType = MediaType.TEXT_HTML, 
	   	                    schema = @Schema(implementation = String.class))
	                }),
		    @APIResponse(
	            responseCode = "500", 
	                description = "Internal server error while downloading resource Base64 encoded.",
	                content = {
	                    @Content(
//	                    	mediaType = MediaType.APPLICATION_OCTET_STREAM, 
	                    		mediaType = MediaType.TEXT_HTML, 
	                    		schema = @Schema(implementation = String.class))
	                })
		  })
	@SecurityRequirements(value = {
		@SecurityRequirement(name = "BasicAuthentication"),
		@SecurityRequirement(name = "BearerAuthentication")})
	@GET
	@Produces(MediaType.TEXT_HTML)
//	@Produces(MediaType.TEXT_PLAIN)
//	@Produces(MediaType.APPLICATION_OCTET_STREAM)
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

//		log.info("GET {}", uriInfo.getRequestUri());
		// Validate user authentication
		Response response = null;
		if (apikey != null) {
			response = authenticate(apikey);
		}
		if (authString != null) {
			response = authenticate(authString);
		}
		if (response != null) {
			// Waste unauthenticated tries some time to avoid bulk attacks
			try {
				Thread.sleep(30000L);
			} catch (InterruptedException e) {
				// Ignore
			}
			return response;
		}
		// Validate input

		try {
			final URL urlOfResource = new URL(url);
			final String fileName = new File(urlOfResource.getPath()).getName();
			final String resourceBase64Encoded = downloadStream(urlOfResource, fileName);
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
			//@formatter:off
			return Response
//				.ok(resourceBase64Encoded)
				.ok(htmlContainer)
//				.header("Content-Disposition", "attachment;filename=" + fileName +".b64")
//				.header(ApiConstants.HEADER_X_HOSTNAME, "AppUtil.getInstance().getHostName()")
				.build();
			//@formatter:on
		} catch (Exception e) {
			e.printStackTrace();
			//@formatter:off
			return Response
				.status(Status.INTERNAL_SERVER_ERROR)
//				.header(ApiConstants.HEADER_X_HOSTNAME, AppUtil.getInstance().getHostName())
//				.header(ApiConstants.HEADER_X_EXCEPTION, ExceptionUtil.getErrorMessageAndCause(e))
//				.entity(ExceptionUtil.getErrorMessageAndCause(e))
				.build();
			//@formatter:on
		}
	}

	/**
	 * Authenticate a user for APIs where {@code Bearer} or {@code Basic} authentications is required.
	 * 
	 * @param authString to authenticate with
	 * @return null (or {@link Response} containing {@code not authorized} if user can't be authenticated)
	 * @throws JsonbException
	 */
	private Response authenticate(final String authString) throws JsonbException {
		String apiKeyAndPassword = "1.0.0";
		if (isUserAuthenticated(authString, apiKeyAndPassword) == false) {
			String validationMessage = "Unauthorized credentials: " + authString
					+ ", no valid Basic or Bearer authentication supplied!";
//			log.warn(validationMessage);
			//@formatter:off
			return Response
				.status(Status.UNAUTHORIZED)
				.entity(JsonbUtil.getInstance().toJson(Status.UNAUTHORIZED.getStatusCode() + " " + Status.UNAUTHORIZED.getReasonPhrase()))
				.header("X-SBS-Message", validationMessage)
				.build();
			//@formatter:on
		}
		return null;
	}

	/**
	 * Authenticate a user for APIs where {@code Bearer} or {@code Basic} authentications is required
	 * 
	 * @param authString        to validate as {@code Bearer} or {@code Basic} authentication
	 * @param apiKeyAndPassword {@code API-Key} for {@code Bearer} or {@code Password} for {@code Basic} authentication
	 * @return true (or false if user can't be authenticated)
	 */
	private boolean isUserAuthenticated(final String authString, final String apiKeyAndPassword) {
		boolean auth = false;
		// Check for supplied credentials
		if (authString == null || authString.contentEquals("")) {
//			log.debug("No authentication data supplied!");
		} else {
			String[] authStringParts = authString.split("\\s+");
			Integer authTokenIndex = null;
			if (authStringParts.length == 1) {
				authTokenIndex = 0;
			} else if (authStringParts.length == 2) {
				if (("Basic".equalsIgnoreCase(authStringParts[0])) || ("Bearer".equalsIgnoreCase(authStringParts[0]))) {
					authTokenIndex = 2;
				}
			}
			if (authTokenIndex != null) {
				// Check for credentials matching configuration properties
				try {
					// Basic or Bearer authentication provided
					String decodedAuth = new String(Base64.getDecoder().decode(authStringParts[1]));
					if (decodedAuth.equals(ApiConstants.APPLICATON + ":" + apiKeyAndPassword)) {
						auth = true;
					}
				} catch (IllegalArgumentException e) {
					// Base64 decoding failed
				}
			}
		}
		return auth;
	}

	/**
	 * 
	 * @param urlOfResource
	 * @param fileName
	 * @return
	 * @throws Exception
	 */
	private String downloadStream(final URL urlOfResource, final String fileName) throws Exception {
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
//				if (/* (chunkCount == 24) || */ (bytesRead == 739)) {
//					System.out.println("!");
//				}
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
		try (FileWriter writer = new FileWriter(FILE_NAME_BASE64, false);
				BufferedWriter bufferedWriter = new BufferedWriter(writer);) {
			bufferedWriter.write(urlStreamBase64Encoded);
		}
		return urlStreamBase64Encoded;
	}

}
