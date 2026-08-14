package edu.java.application;

import javax.ws.rs.ApplicationPath;

import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.info.Contact;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.security.SecuritySchemes;

import edu.java.rest.ApiConstants;

/**
 * JAX-RS application entry point.
 * <p>
 * Declares the OpenAPI metadata (title, version, contact, external docs) and the two global security schemes (Basic and Bearer)
 * that every protected endpoint references. All string values are sourced from {@link Constants} so they are maintained in a
 * single place.
 * </p>
 */
//@formatter:off
@SecuritySchemes(
    value = {
        @SecurityScheme(
            securitySchemeName = ApiConstants.SECURITY_SCHEME_BASIC,
            type = SecuritySchemeType.HTTP,
            scheme = "basic"),
        @SecurityScheme(
            securitySchemeName = ApiConstants.SECURITY_SCHEME_BEARER,
            type = SecuritySchemeType.HTTP,
            scheme = "bearer",
            bearerFormat = "JWT")})
@OpenAPIDefinition(
    externalDocs =
        @ExternalDocumentation(
            description = Constants.APP_DISPLAY_NAME,
            url         = Constants.APP_GITHUB_URL),
    info =
        @Info(
            title          = Constants.APP_DISPLAY_NAME,
            version        = Constants.APP_VERSION,
            termsOfService = Constants.APP_GITHUB_URL,
            contact =
                @Contact(
                    name  = Constants.APP_CONTACT_NAME,
                    email = Constants.APP_CONTACT_EMAIL,
                    url   = Constants.APP_GITHUB_URL)))
//@formatter:on
@ApplicationPath(Constants.API_BASE)
public class Application extends javax.ws.rs.core.Application {
}
