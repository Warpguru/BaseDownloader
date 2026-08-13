package edu.java.rest;

import java.net.URI;
import java.net.URISyntaxException;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.java.application.Constants;
import edu.java.security.AuthFilter;
import edu.java.security.CredentialStore;

/**
 * JAX-RS controller for the login form, session establishment, and logout.
 *
 * <h2>Endpoints</h2>
 * <ul>
 * <li>{@code GET /api/login} — returns an HTML login form. This endpoint is on the {@link AuthFilter} exempt list and therefore
 * reachable without credentials.</li>
 * <li>{@code POST /api/login} — validates the submitted triple (username, password, token) against {@link CredentialStore}. On
 * success it creates an {@link HttpSession}, stores the username under {@link AuthFilter#SESSION_ATTR_USERNAME}, and redirects
 * the browser to the download submission form. On failure it applies the same {@value AuthFilter#BRUTE_FORCE_DELAY_MS} ms delay
 * as {@link AuthFilter} and returns HTTP 401 with the login form again.</li>
 * <li>{@code GET /api/logout} — invalidates the current HTTP session (if any) and returns an HTML confirmation page with a link
 * back to the login form. This endpoint is on the {@link AuthFilter} exempt list so it is reachable even after the session has
 * already expired, making it safe to bookmark and reload. Primarily useful for testing credential changes.</li>
 * </ul>
 *
 * <h2>Session management</h2>
 * <p>
 * Sessions are created with {@link HttpServletRequest#getSession(boolean) getSession(true)} only after successful credential
 * validation, never before. This prevents session-fixation attacks. The session is invalidated by calling
 * {@code GET /api/logout}, which calls {@link HttpSession#invalidate()}.
 * </p>
 *
 * <h2>Brute-force mitigation</h2>
 * <p>
 * A failed login attempt via this endpoint incurs the same {@value AuthFilter#BRUTE_FORCE_DELAY_MS} ms sleep as a failed filter
 * check, ensuring that attackers who use the HTML form rather than the API are equally rate-limited.
 * The logout endpoint does <em>not</em> apply a delay — it is not a credential check.
 * </p>
 */
@Tag(name = "Login WebServices", description = "Application login and session management.")
@Path(ApiConstants.RESOURCE_API_LOGIN)
@Stateless
public class LoginController {

    private static final Logger logger = LoggerFactory.getLogger(LoginController.class);

    @Inject
    private CredentialStore credentialStore;

    // -------------------------------------------------------------------------
    // GET /api/login — serve the login form
    // -------------------------------------------------------------------------

    //@formatter:off
    @Operation(
        summary = "Login form",
        description = "Returns an HTML login form. This endpoint is exempt from authentication "
                + "and is always reachable.")
    @APIResponses(value = {
        @APIResponse(
            responseCode = "200",
            description = "Login form returned successfully.",
            content = @Content(mediaType = MediaType.TEXT_HTML,
                    schema = @Schema(implementation = String.class)))
    })
    //@formatter:on
    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response showLoginForm(@Parameter(hidden = true) @Context final UriInfo uriInfo) {
        logger.info("GET {}", uriInfo.getRequestUri());
        return Response.ok(buildLoginForm(null))
                .build();
    }

    // -------------------------------------------------------------------------
    // POST /api/login — validate credentials and establish session
    // -------------------------------------------------------------------------

    //@formatter:off
    @Operation(
        summary = "Login submit",
        description = "Validates the submitted username, password, and token triple against the "
                + "credential store. On success creates an HTTP session and redirects to the "
                + "download page. On failure returns HTTP 401 with the login form after a "
                + "deliberate delay.")
    @APIResponses(value = {
        @APIResponse(
            responseCode = "303",
            description = "Login successful — redirect to the download submission form."),
        @APIResponse(
            responseCode = "401",
            description = "Invalid credentials.",
            content = @Content(mediaType = MediaType.TEXT_HTML,
                    schema = @Schema(implementation = String.class)))
    })
    //@formatter:on
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response submitLogin(@Parameter(hidden = true) @Context final HttpServletRequest httpRequest,
            @Parameter(hidden = true) @Context final UriInfo uriInfo,
            @Parameter(description = "Username") @FormParam("username") final String username,
            @Parameter(description = "Password") @FormParam("password") final String password,
            @Parameter(description = "API token") @FormParam("token") final String token) {

        logger.info("POST {} user={}", uriInfo.getRequestUri(), username);

        if (credentialStore.validateTriple(username, password, token)) {
            // ── Success: create a NEW session (prevents session fixation) and store username ──
            // Invalidate any pre-existing session first, then get a fresh one
            final HttpSession existing = httpRequest.getSession(false);
            if (existing != null) {
                existing.invalidate();
            }
            final HttpSession session = httpRequest.getSession(true);
            session.setAttribute(AuthFilter.SESSION_ATTR_USERNAME, username);
            logger.info("Login successful for user={}", username);

            // Redirect to the download form
            try {
                final URI downloadUri = new URI(ApiConstants.RESOURCE_API_DOWNLOAD);
                return Response.seeOther(downloadUri)
                        .build();
            } catch (URISyntaxException e) {
                // Should never happen with hardcoded path constants
                return Response.serverError()
                        .build();
            }
        }

        // ── Failure: brute-force delay + 401 with form ──────────────────────────────────────
        logger.warn("Login failed for user={}", username);
        sleepBruteForceDelay();
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(buildLoginForm("Invalid username, password, or token. Please try again."))
                .build();
    }

    // -------------------------------------------------------------------------
    // GET /api/logout — invalidate session
    // -------------------------------------------------------------------------

    //@formatter:off
    @Operation(
        summary = "Logout",
        description = "Invalidates the current HTTP session, removing authentication so a new login "
                + "is required. Returns an HTML confirmation page with a link back to the login form. "
                + "This endpoint is exempt from authentication and is always reachable, even after the "
                + "session has already expired (making it safe to bookmark). Primarily useful for testing.")
    @APIResponses(value = {
        @APIResponse(
            responseCode = "200",
            description = "Session invalidated (or no active session). Logout confirmation page returned.",
            content = @Content(mediaType = MediaType.TEXT_HTML,
                    schema = @Schema(implementation = String.class)))
    })
    //@formatter:on
    @GET
    @Path("logout")
    @Produces(MediaType.TEXT_HTML)
    public Response logout(@Parameter(hidden = true) @Context final HttpServletRequest httpRequest,
            @Parameter(hidden = true) @Context final UriInfo uriInfo) {
        logger.info("GET {}", uriInfo.getRequestUri());
        final HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            final String user = (String) session.getAttribute(AuthFilter.SESSION_ATTR_USERNAME);
            session.invalidate();
            logger.info("Logout: invalidated session for user={}", user);
        } else {
            logger.info("Logout: no active session to invalidate");
        }

        final String loginLink = Constants.CONTEXT_ROOT + "/" + Constants.API_BASE + "/" + ApiConstants.RESOURCE_API_LOGIN;
        final String html = "<!DOCTYPE html><html><head><title>" + Constants.APP_DISPLAY_NAME + " &mdash; Logged out</title></head>"
                + "<body>"
                + "<h2>" + Constants.APP_DISPLAY_NAME + " &mdash; Logged out</h2>"
                + "<p>You have been logged out. Your session has been invalidated.</p>"
                + "<p><a href=\"" + loginLink + "\">Log in again</a></p>"
                + "</body></html>";
        return Response.ok(html).build();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the HTML login form.
     *
     * @param errorMessage an optional error message to display above the form, or {@code null}
     * @return the complete HTML page as a string
     */
    private String buildLoginForm(final String errorMessage) {
        final String action = Constants.CONTEXT_ROOT + "/" + Constants.API_BASE + "/" + ApiConstants.RESOURCE_API_LOGIN;
        final StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><title>").append(Constants.APP_DISPLAY_NAME)
                .append(" &mdash; Login</title></head><body>");
        sb.append("<h2>").append(Constants.APP_DISPLAY_NAME).append(" &mdash; Login</h2>");
        if (errorMessage != null) {
            sb.append("<p style=\"color:#cc0000\">").append(errorMessage).append("</p>");
        }
        sb.append("<form method=\"POST\" action=\"").append(action).append("\">");
        sb.append("<table>");
        sb.append("<tr><td><label for=\"username\">Username:</label></td>")
                .append("<td><input id=\"username\" name=\"username\" type=\"text\" size=\"30\" /></td></tr>");
        sb.append("<tr><td><label for=\"password\">Password:</label></td>")
                .append("<td><input id=\"password\" name=\"password\" type=\"password\" size=\"30\" /></td></tr>");
        sb.append("<tr><td><label for=\"token\">Token:</label></td>")
                .append("<td><input id=\"token\" name=\"token\" type=\"text\" size=\"40\" /></td></tr>");
        sb.append("<tr><td></td><td><input type=\"submit\" value=\"Login\" /></td></tr>");
        sb.append("</table></form>");
        sb.append("<p><small>Alternatively, use Basic or Bearer authentication in the "
                + "<code>Authorization</code> HTTP header for programmatic access.</small></p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * Sleeps for {@link AuthFilter#BRUTE_FORCE_DELAY_MS} ms. Matches the delay applied by {@link AuthFilter} on filter-level
     * failures so that attackers who use the HTML form are equally rate-limited.
     */
    private void sleepBruteForceDelay() {
        try {
            Thread.sleep(AuthFilter.BRUTE_FORCE_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
