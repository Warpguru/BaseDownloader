package edu.java.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Priority;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.java.application.Constants;
import edu.java.rest.ApiConstants;

/**
 * JAX-RS {@link ContainerRequestFilter} that enforces authentication on every request before it reaches a controller.
 *
 * <h2>Design overview</h2>
 * <p>
 * The filter runs at {@link Priorities#AUTHENTICATION} priority, which is the earliest point in the JAX-RS filter chain. For
 * every incoming request it:
 * </p>
 * <ol>
 * <li>Checks whether the requested path is on the <em>exempt list</em> (login endpoints, health checks, metrics, OpenAPI UI).
 * Exempt paths are passed through immediately without any credential check.</li>
 * <li>Attempts to authenticate the caller via one of two mechanisms (tried in order):
 * <ul>
 * <li><strong>HTTP {@code Authorization} header</strong> — Basic or Bearer scheme, validated against {@link CredentialStore}.
 * This path supports programmatic callers and the OpenAPI UI.</li>
 * <li><strong>HTTP session attribute</strong> — a previously established browser session created by
 * {@code POST /api/login}.</li>
 * </ul>
 * </li>
 * <li>On success: stores the authenticated username in the request-scoped {@link RequestContext} bean so controllers can access
 * it, then calls {@link ContainerRequestContext#abortWith} to pass through.</li>
 * <li>On failure: sleeps for {@value #BRUTE_FORCE_DELAY_MS} ms on the current thread to waste attacker bandwidth (rate-limiting
 * without state), then returns HTTP 401. <strong>No redirect is issued</strong> — a redirect would give attackers a fast,
 * redirect-following probe loop.</li>
 * </ol>
 *
 * <h2>Brute-force mitigation</h2>
 * <p>
 * The {@value #BRUTE_FORCE_DELAY_MS} ms sleep runs on the HTTP thread that is serving the failed request. This is intentional:
 * it occupies one Liberty HTTP thread per failed attempt, making high-volume credential-guessing attacks impractical. In this
 * PoC Liberty's default thread pool (50+ threads) means an attacker needs more than 50 concurrent requests to affect legitimate
 * users. In production, consider offloading the delay to an async thread pool.
 * </p>
 *
 * <h2>Session attribute contract</h2>
 * <p>
 * When {@code POST /api/login} succeeds it writes the authenticated username into the session under the key
 * {@link #SESSION_ATTR_USERNAME}. This filter reads that attribute to recognise a browser session without re-checking the
 * credential file on every request. Invalidating the session (logout) immediately removes access.
 * </p>
 *
 * <h2>Exempt paths</h2>
 * <p>
 * The following path prefixes are exempt from authentication. Any request whose URI starts with one of these prefixes is passed
 * through without a credential check:
 * </p>
 * <ul>
 * <li>{@code /base-downloader/api/login} — login form and submit</li>
 * <li>{@code /base-downloader/api/login/logout} — logout</li>
 * <li>{@code /base-downloader/api/info} — health/readiness probe</li>
 * <li>{@code /health} — MicroProfile Health (served by Liberty outside the WAR)</li>
 * <li>{@code /metrics} — MicroProfile Metrics</li>
 * <li>{@code /openapi} — OpenAPI descriptor and UI</li>
 * <li>{@code /api/docs}, {@code /api/explorer} — Liberty OpenAPI UI aliases</li>
 * </ul>
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class AuthFilter implements ContainerRequestFilter {

    private static Logger logger = LoggerFactory.getLogger(AuthFilter.class);

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /**
     * Delay in milliseconds imposed on every failed authentication attempt. This makes high-frequency credential-guessing
     * attacks impractical.
     */
    public static final long BRUTE_FORCE_DELAY_MS = 30_000L;

    /**
     * HTTP session attribute key under which the authenticated username is stored by {@code POST /api/login}. The filter reads
     * this attribute to recognise a live session.
     */
    public static final String SESSION_ATTR_USERNAME = "bd.authenticated.username";

    /**
     * URI prefixes that are exempt from authentication. Any request whose full request URI starts with one of these strings is
     * passed through without a credential check.
     *
     * <p>
     * Paths are matched against the <em>full</em> request URI (context root included) so that the login page itself is always
     * reachable.
     * </p>
     */
    private static final Set<String> EXEMPT_PREFIXES = new HashSet<>(Arrays.asList(
            // Login and logout — must be reachable without a session
            Constants.CONTEXT_ROOT + "/" + Constants.API_BASE + "/" + ApiConstants.RESOURCE_API_LOGIN,
            Constants.CONTEXT_ROOT + "/" + Constants.API_BASE + "/" + ApiConstants.RESOURCE_API_LOGOUT,
            // Health check — must be reachable by load balancers without credentials
            Constants.CONTEXT_ROOT + "/" + Constants.API_BASE + "/" + ApiConstants.RESOURCE_API_INFO,
            // MicroProfile Health (Liberty serves these at the server root, not inside the WAR)
            "/health",
            // MicroProfile Metrics
            "/metrics",
            // OpenAPI descriptor and UI (operators need this to configure Basic/Bearer for testing)
            "/openapi", "/api/docs", "/api/explorer"));

    // -------------------------------------------------------------------------
    // Injected dependencies
    // -------------------------------------------------------------------------

    @Inject
    private CredentialStore credentialStore;

    @Inject
    private RequestContext requestContext;

    /** Injected by the JAX-RS runtime; gives access to the underlying HTTP session. */
    @Context
    private HttpServletRequest httpRequest;

    // -------------------------------------------------------------------------
    // Filter logic
    // -------------------------------------------------------------------------

    /**
     * Main filter method — called by the JAX-RS runtime for every incoming request.
     *
     * <p>
     * The method follows a strict decision tree:
     * </p>
     * <ol>
     * <li>If the request URI matches an exempt prefix → return immediately (no auth check).</li>
     * <li>Try to authenticate via the {@code Authorization} header (Basic or Bearer).</li>
     * <li>If that fails, try to authenticate via an existing HTTP session.</li>
     * <li>If both fail → sleep {@value #BRUTE_FORCE_DELAY_MS} ms, then abort with HTTP 401.</li>
     * <li>If either succeeds → set {@link RequestContext#setUsername} and let the request proceed.</li>
     * </ol>
     *
     * @param ctx the JAX-RS request context; call {@link ContainerRequestContext#abortWith} to short-circuit the request
     */
    @Override
    public void filter(final ContainerRequestContext ctx) throws IOException {
        logger.info("AuthFilter filtering ...");

        // ── Step 1: exempt paths bypass all authentication ────────────────────
        final String requestUri = httpRequest.getRequestURI();
        for (final String prefix : EXEMPT_PREFIXES) {
            if (requestUri.startsWith(prefix)) {
                logger.info("  No authentication required, pass through");
                return; // pass through — no auth required
            }
        }

        // ── Step 2: try Authorization header (Basic or Bearer) ────────────────
        final String authHeader = ctx.getHeaderString("Authorization");
        String authenticatedUser = credentialStore.validateAuthorizationHeader(authHeader);

        // ── Step 3: fall back to HTTP session ─────────────────────────────────
        if (authenticatedUser == null) {
            final HttpSession session = httpRequest.getSession(false); // false = don't create
            if (session != null) {
                authenticatedUser = (String) session.getAttribute(SESSION_ATTR_USERNAME);
            }
        }

        // ── Step 4: both mechanisms failed → brute-force delay + 401 ─────────
        if (authenticatedUser == null) {
            logger.info("  No authentication, rejecting request (after delaying attacker)");
            sleepBruteForceDelay();
            ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .header(ApiConstants.HEADER_X_BD_MESSAGE, "Authentication required. Provide a valid Authorization header "
                            + "(Basic or Bearer) or establish a session via POST /api/login.")
                    .build());
            return;
        }

        // ── Step 5: authenticated — stash username for controllers ────────────
        requestContext.setUsername(authenticatedUser);
        // proceed — do not call abortWith(); returning normally passes the request through
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Sleeps for {@value #BRUTE_FORCE_DELAY_MS} ms on the current thread.
     * <p>
     * This occupies one HTTP thread per failed attempt, making high-frequency credential-guessing loops impractical.
     * InterruptedException is suppressed and the thread's interrupt flag is restored so the container can still shut down
     * cleanly.
     * </p>
     */
    private void sleepBruteForceDelay() {
        try {
            Thread.sleep(BRUTE_FORCE_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restore interrupt flag
        }
    }

}
