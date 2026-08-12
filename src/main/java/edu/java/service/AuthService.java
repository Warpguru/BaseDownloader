package edu.java.service;

import java.util.Base64;

import javax.enterprise.context.ApplicationScoped;
import javax.json.bind.JsonbException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import edu.java.rest.ApiConstants;
import edu.java.util.JsonbUtil;

/**
 * CDI bean responsible for validating API credentials on every protected request.
 * <p>
 * <strong>Credential format:</strong> the expected credential is the application name and the
 * hard-coded password concatenated with a colon ({@code BD:1.0.0}) and then Base64-encoded,
 * exactly as required by the HTTP Basic authentication scheme.  The same Base64-encoded token
 * is also accepted as a Bearer token, so both {@code Authorization: Basic <token>} and
 * {@code Authorization: Bearer <token>} headers are supported.
 * </p>
 * <p>
 * <strong>Brute-force mitigation:</strong> when authentication fails the calling thread is
 * deliberately paused for 30 seconds before the 401 response is returned.  This makes
 * high-frequency credential-guessing attacks impractical.
 * </p>
 * <p>
 * This bean is {@link ApplicationScoped} so that exactly one instance is shared across all
 * concurrent requests; it holds no mutable state, so no locking is required.
 * </p>
 */
@ApplicationScoped
public class AuthService {

	/**
	 * Enforces authentication for a request.
	 * <p>
	 * The check mirrors the two-branch logic previously embedded in {@code DownloadController}:
	 * the {@code apikey} query parameter is evaluated first; if present and invalid a 401 is
	 * returned immediately.  Then the {@code Authorization} header ({@code authString}) is
	 * evaluated the same way.  If neither credential is supplied (both parameters are
	 * {@code null}) the request is considered unauthenticated and a 401 is returned.
	 * </p>
	 * <p>
	 * On authentication failure the method sleeps for 30 seconds as a brute-force mitigation
	 * before returning.
	 * </p>
	 *
	 * @param authString the value of the {@code Authorization} HTTP header, or {@code null} if
	 *                   absent
	 * @param apikey     the value of the {@code apikey} query parameter, or {@code null} if
	 *                   absent
	 * @return {@code null} when the caller is authenticated; a 401 {@link Response} otherwise
	 * @throws JsonbException if JSON serialisation of the error body fails
	 */
	public Response enforceAuth(final String authString, final String apikey) throws JsonbException {
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
		return null;
	}

	/**
	 * Validates a single credential string (either a raw API key or a {@code Basic}/{@code Bearer}
	 * {@code Authorization} header value) against the hard-coded password {@code 1.0.0}.
	 *
	 * @param authString credential to validate
	 * @return {@code null} when the credential is valid; a 401 {@link Response} otherwise
	 */
	private Response authenticate(final String authString) throws JsonbException {
		final String apiKeyAndPassword = "1.0.0";
		if (isUserAuthenticated(authString, apiKeyAndPassword) == false) {
			String validationMessage = "Unauthorized credentials: " + authString
					+ ", no valid Basic or Bearer authentication supplied!";
			//@formatter:off
			return Response
				.status(Status.UNAUTHORIZED)
				.entity(JsonbUtil.getInstance().toJson(Status.UNAUTHORIZED.getStatusCode() + " " + Status.UNAUTHORIZED.getReasonPhrase()))
				.header(ApiConstants.HEADER_X_BD_MESSAGE, validationMessage)
				.build();
			//@formatter:on
		}
		return null;
	}

	/**
	 * Checks whether {@code authString} encodes the expected {@code application:password}
	 * credential.
	 * <p>
	 * Accepted formats:
	 * <ul>
	 *   <li>A single Base64-encoded token (no scheme prefix).</li>
	 *   <li>{@code Basic <token>} or {@code Bearer <token>} where {@code <token>} is the
	 *       Base64 encoding of {@code BD:1.0.0}.</li>
	 * </ul>
	 * </p>
	 *
	 * @param authString        credential string to validate
	 * @param apiKeyAndPassword expected password ({@code 1.0.0})
	 * @return {@code true} if the credential is valid, {@code false} otherwise
	 */
	private boolean isUserAuthenticated(final String authString, final String apiKeyAndPassword) {
		boolean auth = false;
		if (authString == null || authString.contentEquals("")) {
			// No authentication data supplied
		} else {
			// Split the authentication string into parts using whitespace as the delimiter
			String[] authStringParts = authString.split("\\s+");
			if (authStringParts.length ==2) {
	            Integer authTokenIndex = null;
	            if (authStringParts.length == 1) {
	                authTokenIndex = 0;
	            } else if (authStringParts.length == 2) {
	                if (("Basic".equalsIgnoreCase(authStringParts[0])) || ("Bearer".equalsIgnoreCase(authStringParts[0]))) {
	                    authTokenIndex = 1;
	                }
	            }
	            if (authTokenIndex != null) {
	                try {
	                    // Basic or Bearer authentication provided
	                    String decodedAuth = new String(Base64.getDecoder().decode(authStringParts[authTokenIndex]));
	                    if (decodedAuth.equals(ApiConstants.APPLICATON + ":" + apiKeyAndPassword)) {
	                        auth = true;
	                    }
	                } catch (IllegalArgumentException e) {
	                    // Base64 decoding failed — treat as unauthenticated
	                }
	            }
			}
		}
		return auth;
	}

}
