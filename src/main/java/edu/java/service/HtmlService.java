package edu.java.service;

import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.core.Response.Status;

import edu.java.application.Constants;
import edu.java.rest.ApiConstants;

/**
 * CDI service responsible for generating all HTML pages produced by the application.
 *
 * <h2>Design rationale</h2>
 * <p>
 * All styling is <strong>inline</strong> (a single {@code <style>} block embedded in every
 * {@code <head>}). There are no external CSS files to deploy, cache-bust, or serve from a
 * separate URL. The entire appearance is therefore self-contained in the WAR and works
 * identically in any network environment, including air-gapped or offline installations.
 * </p>
 * <p>
 * JavaScript is kept to an absolute minimum. The application targets Java developers and
 * system administrators who regularly inspect HTML source and HTTP traffic. A plain HTML +
 * minimal CSS page is far more readable and auditable than a JavaScript-heavy SPA, and it
 * degrades gracefully when scripting is disabled.
 * </p>
 * <p>
 * This bean is {@link ApplicationScoped} because it holds no mutable state — only constants
 * and string-building logic. A single shared instance is therefore sufficient across all
 * concurrent requests, and no locking is needed.
 * </p>
 *
 * <h2>Page structure</h2>
 * Every page produced by {@link #page} follows this layout:
 * <pre>
 * ┌──────────────────────────────────────────────┐
 * │  HEADER: BaseDownloader vX.Y.Z  GitHub link  │
 * ├──────────────────────────────────────────────┤
 * │  BREADCRUMB: Home &gt; &lt;pageTitle&gt;              │
 * ├──────────────────────────────────────────────┤
 * │  BODY: caller-supplied HTML                  │
 * ├──────────────────────────────────────────────┤
 * │  FOOTER: copyright | OpenAPI UI link         │
 * └──────────────────────────────────────────────┘
 * </pre>
 */
@ApplicationScoped
public class HtmlService {

    // -------------------------------------------------------------------------
    // Shared inline CSS — embedded in every page <head>
    // -------------------------------------------------------------------------

    private static final String STYLE =
        "<style>"
        + "*, *::before, *::after { box-sizing: border-box; }"
        + "body { font-family: Arial, Helvetica, sans-serif; font-size: 14px;"
        +        " margin: 0; padding: 0; background: #f0f4f8; color: #1a1a1a; }"
        + ".page { width: 100%; padding: 1em;"
        +         " background: #ffffff; min-height: 100vh; }"
        + ".site-header { background: #e8f4f8; padding: 0.6em 1em;"
        +                " border-bottom: 2px solid #b0d4e8;"
        +                " display: flex; align-items: center; justify-content: space-between; }"
        + ".site-header h1 { margin: 0; font-size: 1.3em; color: #00426e; }"
        + ".site-header .tagline { font-size: 0.85em; color: #555; margin-left: 0.8em; }"
        + ".header-icons { display: flex; align-items: center; gap: 0.6em; }"
        + ".hdr-icon { display: inline-flex; align-items: center; justify-content: center;"
        +             " width: 32px; height: 32px; border-radius: 6px; color: #00426e;"
        +             " text-decoration: none; transition: background 0.15s; }"
        + ".hdr-icon:hover { background: #c8e4f0; }"
        + ".hdr-icon svg { width: 20px; height: 20px; fill: currentColor; }"
        + ".hdr-user { display: inline-flex; align-items: center; gap: 0.3em;"
        +             " padding: 0.2em 0.5em; border-radius: 6px; color: #00426e;"
        +             " font-size: 0.85em; font-weight: bold; cursor: default; }"
        + ".hdr-user svg { width: 18px; height: 18px; fill: currentColor; flex-shrink: 0; }"
        + ".hdr-sep { color: #aac8d8; font-size: 1.2em; line-height: 1; }"
        + ".breadcrumb { font-size: 0.85em; color: #555; padding: 0.4em 0;"
        +               " border-bottom: 1px solid #e0e0e0; margin-bottom: 1em; }"
        + ".breadcrumb a { color: #0066cc; text-decoration: none; }"
        + ".breadcrumb a:hover { text-decoration: underline; }"
        + "h2 { color: #00426e; margin-top: 0.5em; }"
        + "h3 { color: #005590; margin-top: 1em; }"
        + "table { border-collapse: collapse; width: 100%; margin-bottom: 1em; }"
        + "th, td { border: 1px solid #ccc; padding: 0.3em 0.6em; text-align: left;"
        +           " vertical-align: top; }"
        + "thead th { background: #ddeeff; font-weight: bold; }"
        + "tbody tr:nth-child(even) { background: #f9f9f9; }"
        + "pre { background: #f4f4f4; padding: 0.5em 0.8em;"
        +       " border-left: 3px solid #0066cc; overflow-x: auto; margin: 0.3em 0; }"
        + "code { background: #f0f0f0; padding: 0.1em 0.3em; border-radius: 2px; }"
        + ".btn-copy { border: 1px solid #bbb; background: #f8f8f8; cursor: pointer;"
        +              " font-size: 0.85em; padding: 0.1em 0.4em; border-radius: 3px;"
        +              " vertical-align: middle; }"
        + ".btn-copy:hover { background: #e0e8f0; }"
        + ".badge-done     { color: #006600; font-weight: bold; }"
        + ".badge-failed   { color: #cc0000; font-weight: bold; }"
        + ".badge-progress { color: #cc6600; font-weight: bold; }"
        + ".error-box { background: #fff0f0; border: 1px solid #ffaaaa;"
        +               " padding: 1em; border-left: 4px solid #cc0000; margin: 1em 0; }"
        + ".success-box { background: #f0fff0; border: 1px solid #aaffaa;"
        +                 " padding: 1em; border-left: 4px solid #006600; margin: 1em 0; }"
        + "form table td { border: none; padding: 0.3em 0.5em; }"
        + "input[type=text], input[type=password], textarea {"
        +   " border: 1px solid #bbb; padding: 0.3em 0.5em; border-radius: 3px;"
        +   " font-size: 14px; width: 100%; }"
        + "input[type=submit], button.submit {"
        +   " background: #0066cc; color: #fff; border: none; padding: 0.4em 1.2em;"
        +   " font-size: 14px; border-radius: 3px; cursor: pointer; }"
        + "input[type=submit]:hover, button.submit:hover { background: #0055aa; }"
        + ".site-footer { border-top: 1px solid #ddd; margin-top: 2em; padding-top: 0.5em;"
        +                 " font-size: 0.8em; color: #888; text-align: center; }"
        + ".flex-row { display: flex; align-items: baseline; gap: 0.4em; margin: 0.3em 0; }"
        + "</style>";

    // -------------------------------------------------------------------------
    // Page assembly
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Inline SVG icon paths (no external resources, no icon fonts)
    // -------------------------------------------------------------------------

    /** GitHub mark (official simplified path). */
    private static final String SVG_GITHUB =
        "<svg viewBox=\"0 0 16 16\" xmlns=\"http://www.w3.org/2000/svg\">"
        + "<path d=\"M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38"
        + " 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13"
        + "-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66"
        + ".07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15"
        + "-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82a7.65 7.65 0 0 1 2-.27c.68 0 1.36.09 2 .27"
        + " 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15"
        + " 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2"
        + " 0 .21.15.46.55.38A8.013 8.013 0 0 0 16 8c0-4.42-3.58-8-8-8z\"/>"
        + "</svg>";

    /** External-link / OpenAPI icon (box with arrow). */
    private static final String SVG_OPENAPI =
        "<svg viewBox=\"0 0 24 24\" xmlns=\"http://www.w3.org/2000/svg\">"
        + "<path d=\"M19 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2z"
        + "M10 17H7v-7h3v7zm-1.5-8a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zM17 17h-3v-3.5"
        + " 0-.83-.67-1.5-1.5-1.5S11 12.67 11 13.5V17H8v-7h3v1.17A3.49 3.49 0 0 1 14 10"
        + "a3.5 3.5 0 0 1 3.5 3.5V17z\"/>"
        + "</svg>";

    /** Person / user icon. */
    private static final String SVG_USER =
        "<svg viewBox=\"0 0 24 24\" xmlns=\"http://www.w3.org/2000/svg\">"
        + "<path d=\"M12 12a5 5 0 1 0 0-10 5 5 0 0 0 0 10zm0 2c-5.33 0-8 2.67-8 4v1h16v-1"
        + "c0-1.33-2.67-4-8-4z\"/>"
        + "</svg>";

    /** Door / logout icon. */
    private static final String SVG_LOGOUT =
        "<svg viewBox=\"0 0 24 24\" xmlns=\"http://www.w3.org/2000/svg\">"
        + "<path d=\"M10.09 15.59L11.5 17l5-5-5-5-1.41 1.41L12.67 11H3v2h9.67l-2.58 2.59z\"/>"
        + "<path d=\"M19 3H5a2 2 0 0 0-2 2v4h2V5h14v14H5v-4H3v4a2 2 0 0 0 2 2h14"
        + "a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2z\"/>"
        + "</svg>";

    // -------------------------------------------------------------------------
    // Page assembly
    // -------------------------------------------------------------------------

    /**
     * Builds a complete HTML page with the standard header, breadcrumb, body, and footer.
     * The header shows no authenticated user (suitable for login/logout pages).
     *
     * @param pageTitle the human-readable page title
     * @param bodyHtml  pre-built body content
     * @return complete HTML document as a string
     */
    public String page(final String pageTitle, final String bodyHtml) {
        return page(pageTitle, bodyHtml, "", null);
    }

    /**
     * Builds a complete HTML page with optional extra {@code <head>} content.
     * The header shows no authenticated user.
     *
     * @param pageTitle     human-readable page title
     * @param bodyHtml      pre-built body content
     * @param extraHeadHtml additional HTML inside {@code <head>} (may be empty)
     * @return complete HTML document as a string
     */
    public String page(final String pageTitle, final String bodyHtml, final String extraHeadHtml) {
        return page(pageTitle, bodyHtml, extraHeadHtml, null);
    }

    /**
     * Builds a complete HTML page with optional extra {@code <head>} content and an
     * authenticated-user badge in the header.
     * <p>
     * Use {@code extraHeadHtml} to insert a {@code <meta http-equiv="refresh">} tag or a
     * minimal {@code <script>} block. Pass {@code username} (from {@link edu.java.security.RequestContext})
     * to show the logged-in user as a non-clickable icon badge in the header.
     * </p>
     *
     * @param pageTitle     human-readable page title
     * @param bodyHtml      pre-built body content
     * @param extraHeadHtml additional HTML inside {@code <head>} (may be empty or null)
     * @param username      the authenticated username to display in the header, or {@code null}
     * @return complete HTML document as a string
     */
    public String page(final String pageTitle, final String bodyHtml,
            final String extraHeadHtml, final String username) {
        final String homeLink = Constants.CONTEXT_ROOT + "/" + Constants.API_BASE
                + "/" + ApiConstants.RESOURCE_API_DOWNLOAD;
        final String openApiLink = "/openapi/ui";
        final String logoutLink  = Constants.CONTEXT_ROOT + "/api/login/logout";

        final StringBuilder sb = new StringBuilder(2048);
        sb.append("<!DOCTYPE html><html lang=\"en\"><head>")
          .append("<meta charset=\"UTF-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
          .append("<title>").append(esc(pageTitle)).append(" &mdash; ").append(Constants.APP_DISPLAY_NAME)
          .append("</title>")
          .append(STYLE);
        if (extraHeadHtml != null && !extraHeadHtml.isEmpty()) {
            sb.append(extraHeadHtml);
        }
        sb.append("</head><body><div class=\"page\">");

        // ── Header ────────────────────────────────────────────────────────────
        sb.append("<header class=\"site-header\">")
          .append("<div style=\"display:flex;align-items:baseline\">")
          .append("<h1>").append(Constants.APP_DISPLAY_NAME)
          .append(" <small style=\"font-size:0.7em;color:#555\">v").append(Constants.APP_VERSION).append("</small></h1>")
          .append("<span class=\"tagline\">Base64 chunked downloader</span>")
          .append("</div>")
          .append("<div class=\"header-icons\">");

        // GitHub icon
        sb.append("<a class=\"hdr-icon\" href=\"").append(Constants.APP_GITHUB_URL)
          .append("\" target=\"_blank\" title=\"").append(escAttr(Constants.APP_GITHUB_URL)).append("\">")
          .append(SVG_GITHUB).append("</a>");

        sb.append("<span class=\"hdr-sep\">|</span>");

        // OpenAPI UI icon
        sb.append("<a class=\"hdr-icon\" href=\"").append(openApiLink)
          .append("\" target=\"_blank\" title=\"").append(openApiLink).append("\">")
          .append(SVG_OPENAPI).append("</a>");

        sb.append("<span class=\"hdr-sep\">|</span>");

        // User badge — non-clickable, shows username as tooltip
        final String displayUser = (username != null && !username.isEmpty()) ? username : "anonymous";
        sb.append("<span class=\"hdr-user\" title=\"").append(escAttr(displayUser)).append("\">")
          .append(SVG_USER)
          .append("</span>");

        // Logout icon
        sb.append("<a class=\"hdr-icon\" href=\"").append(logoutLink)
          .append("\" title=\"Logout (").append(escAttr(displayUser)).append(")\">")
          .append(SVG_LOGOUT).append("</a>");

        sb.append("</div></header>");

        // Breadcrumb
        sb.append("<nav class=\"breadcrumb\">")
          .append("<a href=\"").append(homeLink).append("\">Home</a>")
          .append(" &rsaquo; ").append(esc(pageTitle))
          .append("</nav>");

        // Body
        sb.append("<main>")
          .append(bodyHtml)
          .append("</main>");

        // Footer
        sb.append("<footer class=\"site-footer\">")
          .append("&copy; ").append(Constants.APP_DISPLAY_NAME)
          .append(" &nbsp;|&nbsp; <a href=\"").append(Constants.APP_GITHUB_URL)
          .append("\" target=\"_blank\">").append(Constants.APP_GITHUB_URL).append("</a>")
          .append("</footer>");

        sb.append("</div></body></html>");
        return sb.toString();
    }

    /**
     * Builds a minimal error page with a styled error box.
     *
     * @param status  JAX-RS {@link Status} constant (e.g. {@link Status#BAD_REQUEST})
     * @param message human-readable error description
     * @return complete HTML error page as a string
     */
    public String errorPage(final Status status, final String message) {
        return errorPage(status, message, null);
    }

    /**
     * Builds a minimal error page with a styled error box and an authenticated-user badge.
     *
     * @param status   JAX-RS {@link Status} constant (e.g. {@link Status#NOT_FOUND})
     * @param message  human-readable error description
     * @param username the authenticated username, or {@code null}
     * @return complete HTML error page as a string
     */
    public String errorPage(final Status status, final String message, final String username) {
        final int code = status.getStatusCode();
        final String reason = status.getReasonPhrase();
        final String body = "<div class=\"error-box\">"
                + "<strong>" + code + " " + reason + "</strong> &mdash; " + esc(message)
                + "</div>"
                + "<p><a href=\"" + Constants.CONTEXT_ROOT + "/" + Constants.API_BASE
                + "/" + ApiConstants.RESOURCE_API_DOWNLOAD + "\">Back to downloads</a></p>";
        return page(code + " " + reason, body, "", username);
    }

    // -------------------------------------------------------------------------
    // Reusable HTML fragments
    // -------------------------------------------------------------------------

    /**
     * Builds a styled HTML table with a {@code <thead>} row and {@code <tbody>} rows.
     *
     * @param headers column header labels
     * @param rows    list of row data arrays; each array must have the same length as
     *                {@code headers}; values are inserted verbatim (may contain HTML)
     * @return {@code <table>} element as a string
     */
    public String table(final String[] headers, final List<String[]> rows) {
        final StringBuilder sb = new StringBuilder(512);
        sb.append("<table><thead><tr>");
        for (final String h : headers) {
            sb.append("<th>").append(h).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        for (final String[] row : rows) {
            sb.append("<tr>");
            for (final String cell : row) {
                sb.append("<td>").append(cell != null ? cell : "").append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    /**
     * Returns a coloured {@code <span>} badge for the given download-task status.
     * <ul>
     *   <li>{@code DONE} &rarr; green</li>
     *   <li>{@code FAILED} &rarr; red</li>
     *   <li>{@code IN_PROGRESS} / {@code PENDING} &rarr; orange</li>
     * </ul>
     *
     * @param status the task status
     * @return HTML {@code <span>} element
     */
    public String statusBadge(final DownloadTask.Status status) {
        final String cls;
        switch (status) {
            case DONE:        cls = "badge-done";     break;
            case FAILED:      cls = "badge-failed";   break;
            default:          cls = "badge-progress"; break;
        }
        return "<span class=\"" + cls + "\">" + status.name() + "</span>";
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * HTML-encodes the five characters that are significant in HTML text content:
     * {@code &}, {@code <}, {@code >}, {@code "}, {@code '}.
     * Use this for any user-supplied or URL-derived string placed inside HTML text or
     * attribute values.
     *
     * @param s raw string; {@code null} is treated as an empty string
     * @return HTML-safe string
     */
    public static String esc(final String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * HTML-encodes only {@code &} and {@code "} — sufficient for content placed inside a
     * double-quoted HTML <em>attribute</em> value that is already known to contain no
     * angle-bracket characters (e.g. filesystem paths in {@code data-cmd} attributes).
     *
     * @param s raw string
     * @return attribute-safe string
     */
    public static String escAttr(final String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("\"", "&quot;");
    }

}
