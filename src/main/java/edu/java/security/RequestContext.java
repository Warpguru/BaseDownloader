package edu.java.security;

import javax.enterprise.context.RequestScoped;

/**
 * Request-scoped CDI bean that carries the authenticated username for the duration of a single HTTP request.
 *
 * <h2>How it is populated</h2>
 * <p>
 * {@link AuthFilter} sets {@link #setUsername(String)} immediately after a request passes authentication. Controllers that need
 * the caller's identity (e.g. to record who submitted a download) inject this bean and call {@link #getUsername()}.
 * </p>
 *
 * <h2>Why {@code @RequestScoped}?</h2>
 * <p>
 * A new instance is created for every HTTP request and destroyed when the response is committed. This avoids any cross-request
 * state leakage that would occur with a wider scope. The JAX-RS filter and the controller both run within the same request
 * scope, so they share the same bean instance.
 * </p>
 */
@RequestScoped
public class RequestContext {

    /**
     * The username of the authenticated caller, or {@code null} if the request reached this point without authentication
     * (should not happen in normal operation since {@link AuthFilter} rejects unauthenticated requests before they reach any
     * controller).
     */
    private String username;

    /**
     * Returns the authenticated username.
     *
     * @return username, or {@code null} if not set
     */
    public String getUsername() {
        return username;
    }

    /**
     * Sets the authenticated username. Called once by {@link AuthFilter} after successful credential validation.
     *
     * @param username the authenticated username
     */
    public void setUsername(final String username) {
        this.username = username;
    }

}
