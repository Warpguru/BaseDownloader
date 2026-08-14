package edu.java.rest;

import java.io.File;
import java.net.URL;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.core.Response.Status;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.java.security.RequestContext;
import edu.java.service.HtmlService;
import edu.java.service.StreamDownloadService;

/**
 * JAX-RS controller for the legacy single-stream Base64-download endpoint ({@code GET /api/download}).
 * <p>
 * This controller is intentionally thin: authentication is handled by the JAX-RS {@code AuthFilter} before any controller
 * method is invoked; all stream-download / Base64-encoding logic is delegated to {@link StreamDownloadService}; and HTML page
 * chrome is provided by {@link HtmlService}. The controller itself contains only HTTP glue code.
 * </p>
 * <p>
 * {@code @Stateless} is used (not {@code @Singleton}) so the EJB container can serve concurrent requests from a pool of
 * instances without serialising access via the default write-lock that {@code @Singleton} would impose. This controller holds
 * no mutable instance state.
 * </p>
 */
@Tag(name = "Download WebServices", description = "Base64 download WebServices.")
@Path(ApiConstants.RESOURCE_API_DOWNLOAD)
@Stateless
public class DownloadController {

    // Sample download urls:
    // https://repo1.maven.org/maven2/com/github/javadev/qrcode-generator/1.1/qrcode-generator-1.1.jar
    // https://repo1.maven.org/maven2/log4j/log4j/1.2.17/log4j-1.2.17.zip
    // ftp://demo:password@test.rebex.net/pub/example/KeyGenerator.png

    private static final Logger logger = LoggerFactory.getLogger(DownloadController.class);

    @Inject
    private StreamDownloadService streamDownloadService;

    @Inject
    private HtmlService htmlService;

    @Inject
    private RequestContext requestContext;

    //@formatter:off
	@Operation(
		summary = "Download WebService",
		description = "Download a resource Base64 encoded WebService.")
	@APIResponses(
		value = {
		    @APIResponse(
		        responseCode = "200",
		            description = "Resource downloaded and returned Base64-encoded in an HTML container.",
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
		@SecurityRequirement(name = ApiConstants.SECURITY_SCHEME_BASIC),
		@SecurityRequirement(name = ApiConstants.SECURITY_SCHEME_BEARER)})
	@GET
	@Produces(MediaType.TEXT_HTML)
	@Counted(name = "BD_Counted_DownloadController_Base64Download", displayName = "DownloadController", description = "Download API counter.", absolute = true, unit = MetricUnits.NONE)
	public Response base64Download(
            @Parameter(hidden = true) @Context final UriInfo uriInfo,
			@Parameter(description = "Url to resource to Base64 encode", in = ParameterIn.QUERY, required = true, allowEmptyValue = false,
					examples = {
						@ExampleObject(name = "QRCode generator (small) zipfile HTTPS download", value = "https://repo1.maven.org/maven2/com/github/javadev/qrcode-generator/1.1/qrcode-generator-1.1.jar"),
						@ExampleObject(name = "Process Explorer (large) zipfile HTTPS download", value = "https://download.sysinternals.com/files/ProcessExplorer.zip"),
						@ExampleObject(name = "Unzip (small) executable HTTPS download", value = "https://download.informer.com/win-1193253362-2ecfd01d-62ac9ff1-8e4fcae4627b572817-b2d9117af2b0bcdf2-937370509-1191930848/unzipme.exe"),
                        @ExampleObject(name = "Sample (small) image FTP download", value = "ftp://demo:password@test.rebex.net/pub/example/KeyGenerator.png")
			        },
					schema = @org.eclipse.microprofile.openapi.annotations.media.Schema(implementation = String.class))
			@QueryParam("url") final String url) {
		//@formatter:on
        logger.info("GET {} url={}", uriInfo.getRequestUri(), url);
        try {
            final URL urlOfResource = new URL(url);
            final String fileName = new File(urlOfResource.getPath()).getName();
            final String resourceBase64Encoded = streamDownloadService.downloadStream(urlOfResource, fileName);
            final String b64FileName = HtmlService.escAttr(fileName + ".b64");

            // Minimal JS injected into <head>:
            // copyB64() — copies textarea value to clipboard (Clipboard API, no size limit)
            // saveB64() — downloads the Base64 text as <fileName>.b64 (Blob, no size limit)
            // decodeSave() — decodes Base64 entirely in the browser and downloads the binary file.
            // Uses chunked atob() to stay within JS string limits and keep peak
            // memory manageable for large files. Zero server round-trips.
            final String rawFileName = HtmlService.escAttr(fileName);
            //@formatter:off
            final String extraHead = "<script>" + "function copyB64(){" + "var t=document.getElementById('b64out');"
                    + "navigator.clipboard.writeText(t.value).then(function(){" + "var b=document.getElementById('btn-copy');"
                    + "var prev=b.textContent;b.textContent='\\u2713 Copied!';"
                    + "setTimeout(function(){b.textContent=prev;},1500);" + "});}" + "function saveB64(){"
                    + "var t=document.getElementById('b64out');" + "var blob=new Blob([t.value],{type:'text/plain'});"
                    + "triggerDownload(blob,'" + b64FileName + "');}"
                    // Decode Base64 → binary entirely in the browser, chunk by chunk so that
                    // atob() never receives a string larger than 64 KiB of Base64 at a time
                    // (each Base64 chunk decodes to at most 48 KiB of binary data).
                    // The resulting Uint8Array slices are collected and wrapped in a Blob —
                    // no data: URI is ever constructed, so there is no browser size cap.
                    + "function decodeSave(){" + "var b64=document.getElementById('b64out').value.replace(/\\s/g,'');"
                    + "var chunkSize=65536;" // 64 KiB of Base64 chars per atob() call
                    + "var parts=[];" + "for(var i=0;i<b64.length;i+=chunkSize){" + "var slice=b64.slice(i,i+chunkSize);"
                    + "var bin=atob(slice);" + "var bytes=new Uint8Array(bin.length);"
                    + "for(var j=0;j<bin.length;j++)bytes[j]=bin.charCodeAt(j);" + "parts.push(bytes);}"
                    + "var blob=new Blob(parts,{type:'application/octet-stream'});" + "triggerDownload(blob,'" + rawFileName
                    + "');}" + "function triggerDownload(blob,name){" + "var a=document.createElement('a');"
                    + "a.href=URL.createObjectURL(blob);" + "a.download=name;" + "a.click();" + "URL.revokeObjectURL(a.href);}"
                    + "</script>";

            final String body = "<h2>" + HtmlService.esc(fileName) + "</h2>"
                    + "<p>"
                    + "<button id=\"btn-copy\" class=\"btn-copy\" onclick=\"copyB64()\" title=\"Copy Base64 content to clipboard\">&#x2398; Copy to clipboard</button>"
                    + "&thinsp;"
                    + "<button class=\"btn-copy\" onclick=\"saveB64()\" title=\"Download Base64 text as " + b64FileName + "\">&#x2913; Download " + HtmlService.esc(fileName + ".b64") + "</button>"
                    + "&thinsp;"
                    + "<button class=\"btn-copy\" onclick=\"decodeSave()\" title=\"Decode Base64 and download binary file " + rawFileName + " (runs entirely in the browser)\">&#x2913; Download " + HtmlService.esc(fileName) + "</button>"
                    + "</p>"
                    + "<textarea id=\"b64out\" readonly rows=\"20\""
                    + " style=\"font-family:Consolas,'Courier New',monospace;font-size:12px;"
                    + "width:100%;resize:vertical;word-break:break-all;white-space:pre-wrap;"
                    + "background:#f4f4f4;border:1px solid #ccc;padding:0.5em;border-radius:3px;\">"
                    + resourceBase64Encoded
                    + "</textarea>";
            return Response.ok(htmlService.page(fileName, body, extraHead, requestContext.getUsername()))
                    .build();
            //@formatter:on
        } catch (Exception e) {
            e.printStackTrace();
            //@formatter:off
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                    .entity(htmlService.errorPage(Status.INTERNAL_SERVER_ERROR, "Error downloading resource: " + HtmlService.esc(e.getMessage()), requestContext.getUsername()))
                    .build();
            //@formatter:on
        }
    }

}
