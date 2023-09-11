package edu.java.rest;

import javax.ws.rs.ApplicationPath;

import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.info.Contact;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.security.SecuritySchemes;

//@formatter:off
@SecuritySchemes(
	value = { 
		@SecurityScheme(
		securitySchemeName = "BasicAuthentication", 
		type = SecuritySchemeType.HTTP,
		scheme = "basic"), 
		@SecurityScheme(
		securitySchemeName = "BearerAuthentication", 
		type = SecuritySchemeType.HTTP,
		scheme = "bearer",
		bearerFormat = "JWT")})
@OpenAPIDefinition(
	externalDocs = 
		@ExternalDocumentation(
			description = "BaseDownloader",
			url = "http://github.com/blabla"),
			info = 
				@Info(
					title="BaseDownloader", 
					version = "1.0.0", 
					termsOfService = "http://github.com/blabla",
					contact = 
						@Contact(
							name = "Roman Stangl",
							email = "Roman.Stangl@gmx.net",
							url = "http://github.com/blabla")))
//@formatter:on
@ApplicationPath(ApiConstants.RESOURCE_API_APPLICATON)
public class Application extends javax.ws.rs.core.Application {
}
