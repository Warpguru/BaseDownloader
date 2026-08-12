# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Project Overview

**BaseDownloader** is a Jakarta EE REST web service running on IBM Liberty. The primary feature is downloading resources from URLs and returning them Base64-encoded.

**Stack**: Java 8, Maven, Jakarta EE (jakartaee-8.0), IBM Liberty, Eclipse MicroProfile 4.1

## Build & Test Commands

```bash
# Build WAR file (creates target/base-downloader.war)
mvn clean package

# Start Liberty server with application deployed
mvn liberty:run

# Stop the running Liberty server
mvn liberty:stop

# Runs tests (no tests currently defined in project)
mvn test
```

**Key**: The project builds a WAR file named `base-downloader` deployed to Liberty at context root `/base-downloader`.

## Code Organization & Architecture

### Entry Points
- **REST Application**: [`src/main/java/edu/java/rest/Application.java`](src/main/java/edu/java/rest/Application.java) - Defines OpenAPI (Swagger) metadata
- **Controllers**: 
  - [`DownloadController`](src/main/java/edu/java/rest/DownloadController.java) at `/api/base` - Main API endpoint for downloading/encoding
  - [`InfoController`](src/main/java/edu/java/rest/InfoController.java) at `/api/info` - Health check endpoint

### Critical Patterns

1. **Hardcoded Authentication**: Both Basic and Bearer auth are validated against hardcoded string `"1.0.0"` (see line 180 in DownloadController). The application name is `"BD"` (ApiConstants.APPLICATON).

2. **Singleton Controllers**: Both REST controllers use `@Singleton` EJB, meaning they are application-scoped and thread-safe state must be managed carefully.

3. **JsonbUtil Caching**: JSON serialization uses a cached `Jsonb` singleton (line 19 in JsonbUtil.java). Never recreate JsonbBuilder; always call `JsonbUtil.getInstance()` for JSON operations.

4. **Stream Buffering Logic**: Base64 encoding is done in 1KB chunks with remainder bytes held in a clipboard buffer. The algorithm ensures Base64 encoding aligns to 3-byte boundaries. Do not modify the buffer math in `downloadStream()` without understanding the 3-byte alignment requirement (line 264 in DownloadController).

5. **Delayed Auth Failure Response**: Unauthenticated requests trigger a 30-second sleep (line 128) as a brute-force mitigation. This is intentional and should not be removed.

6. **File I/O During Download**: The controller writes both the raw binary file (as `test.zip`) and Base64-encoded version (as `test.b64`) to the local filesystem during download. Files are always overwritten (not appended).

## Code Style Guidelines

### Imports & Packages
- Use Jakarta EE imports (`javax.ws.rs.*`, `javax.ejb.*`, not `com.sun.*`)
- Organize imports: standard library, then `javax.*`, then `org.eclipse.microprofile.*`
- Package naming: `edu.java.rest` for REST controllers and utilities

### Naming Conventions
- **Constants**: ALL_CAPS with `static final` (e.g., `BUFFER_LENGTH_STREAM`)
- **Methods**: camelCase, use verb-first naming (e.g., `isUserAuthenticated()`, `downloadStream()`)
- **Classes**: PascalCase, suffix with `Controller` for REST endpoints, `Util` for utilities
- **Fields**: camelCase, prefix with `final` if immutable

### Formatting & Comments
- Use `//@formatter:off` and `//@formatter:on` to disable formatter around complex annotations (seen throughout DownloadController)
- Single-line comments for inline logic; Javadoc (`/** */`) for public methods
- Keep methods focused on a single responsibility

### Exception Handling
- **Never silently ignore exceptions**: All catch blocks must either log, rethrow, or handle explicitly
- Use `e.printStackTrace()` only for debug; production code should use proper logging (currently commented out, see line 116)
- Catch `IOException` explicitly in stream operations; wrap lower-level exceptions with context

### Type Handling
- Use Java 8 collections API (no streams/lambdas appear in this codebase)
- Array operations: use `System.arraycopy()` for efficient bulk copying
- String operations: use `contentEquals()` for null-safe comparison (not `==` or `.equals()` on potentially null strings)

### REST/OpenAPI Annotations
- **Always document endpoints** with `@Operation`, `@APIResponses`, `@Parameter`, and `@Tag`
- **Examples in OpenAPI**: Use `@ExampleObject` to provide real-world sample inputs (see DownloadController line 107-110)
- **Security annotations**: Declare `@SecurityRequirements` on every endpoint requiring auth; both Basic and Bearer schemes are defined globally in Application.java

### Error Responses
- Return `Response.status(Status.XXX).build()` for errors, not exceptions (REST convention)
- Include meaningful error context in `X-BD-Message` header when available
- Do not expose stack traces to clients; log internally only

## Testing

**No tests currently exist** in the project. If tests are added:
- Use Maven Surefire plugin (add to pom.xml if needed)
- Place tests in `src/test/java` following the same package structure as source
- Test naming convention: `{ClassName}Test.java`
- Run single test: `mvn test -Dtest=ClassName`

## Liberty Server Configuration

Configuration is in `src/main/liberty/config/server.xml`. Key settings:
- HTTP endpoint: port `${default.http.port}` (default 9080), HTTPS: `${default.https.port}` (default 9443)
- Application deployed at context root `/base-downloader`
- Features enabled: jakartaee-8.0, openapi-3.1, adminCenter-1.0, restConnector-2.0
- Metrics collection: `/metrics` endpoint active (mpMetrics)
- Access logging enabled (see httpEndpoint accessLogging)

Override ports via `liberty-maven-plugin` or environment variables in Liberty's `bootstrap.properties`.

## Known Gotchas

1. **Base64 Alignment**: The 3-byte alignment buffer in `downloadStream()` is required for correct encoding. The clipboard buffer carries remainder bytes between chunks.

2. **File Overwrite**: Every download creates new `test.zip` and `test.b64` files, overwriting previous runs. Use unique filenames if concurrent downloads are needed.

3. **Authentication**: Current auth is hardcoded; there is no external auth service. Both Basic (`user:1.0.0`) and Bearer (`token:1.0.0`) accept the same credential.

4. **Thread Safety**: Controllers are `@Singleton` with no explicit synchronization; concurrent requests may race on file I/O. Consider adding a lock or using unique temp files if concurrency is required.

5. **Formatter Directives**: The code uses `//@formatter:off/on` extensively. Respect these directives; do not reformat annotated blocks.
