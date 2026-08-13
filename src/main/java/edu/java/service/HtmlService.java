package edu.java.service;

import java.util.List;

import javax.enterprise.context.ApplicationScoped;

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
        +                " display: flex; align-items: baseline; justify-content: space-between; }"
        + ".site-header h1 { margin: 0; font-size: 1.3em; color: #00426e; }"
        + ".site-header .tagline { font-size: 0.85em; color: #555; margin-left: 0.8em; }"
        + ".site-header .header-links { font-size: 0.85em; white-space: nowrap; }"
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

    /**
     * Builds a complete HTML page with the standard header, breadcrumb, body, and footer.
     *
     * @param pageTitle the human-readable page title shown in {@code <title>}, the
     *                  breadcrumb, and the {@code <h2>}
     * @param bodyHtml  pre-built body content (caller assembles data; this method adds chrome)
     * @return complete HTML document as a string
     */
    public String page(final String pageTitle, final String bodyHtml) {
        return page(pageTitle, bodyHtml, "");
    }

    /**
     * Builds a complete HTML page with optional extra content injected into {@code <head>}.
     * <p>
     * Use {@code extraHeadHtml} to insert a {@code <meta http-equiv="refresh">} tag (for the
     * auto-refreshing status page) or the minimal {@code <script>} block for the copy buttons.
     * </p>
     *
     * @param pageTitle     human-readable page title
     * @param bodyHtml      pre-built body content
     * @param extraHeadHtml additional HTML to place inside {@code <head>} (may be empty)
     * @return complete HTML document as a string
     */
    public String page(final String pageTitle, final String bodyHtml, final String extraHeadHtml) {
        final String homeLink = Constants.CONTEXT_ROOT + "/" + Constants.API_BASE
                + "/" + ApiConstants.RESOURCE_API_DOWNLOAD;
        final String openApiLink = "/openapi/ui";

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

        // Header
        sb.append("<header class=\"site-header\">")
          .append("<div><h1>").append(Constants.APP_DISPLAY_NAME)
          .append(" <small style=\"font-size:0.7em;color:#555\">v").append(Constants.APP_VERSION).append("</small></h1>")
          .append("<span class=\"tagline\">Base64 chunked downloader</span></div>")
          .append("<div class=\"header-links\">")
          .append("<a href=\"").append(Constants.APP_GITHUB_URL).append("\" target=\"_blank\">GitHub</a>")
          .append(" &nbsp;|&nbsp; ")
          .append("<a href=\"").append(openApiLink).append("\" target=\"_blank\">OpenAPI UI</a>")
          .append(" &nbsp;|&nbsp; ")
          .append("<a href=\"").append(Constants.CONTEXT_ROOT).append("/api/login/logout\">Logout</a>")
          .append("</div>")
          .append("</header>");

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
     * @param httpStatus HTTP status code (e.g. 400, 401, 404, 500)
     * @param message    human-readable error description
     * @return complete HTML error page as a string
     */
    public String errorPage(final int httpStatus, final String message) {
        final String body = "<div class=\"error-box\">"
                + "<strong>Error " + httpStatus + "</strong> &mdash; " + esc(message)
                + "</div>"
                + "<p><a href=\"" + Constants.CONTEXT_ROOT + "/" + Constants.API_BASE
                + "/" + ApiConstants.RESOURCE_API_DOWNLOAD + "\">Back to downloads</a></p>";
        return page("Error " + httpStatus, body);
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
