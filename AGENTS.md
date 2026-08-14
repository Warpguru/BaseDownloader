# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Project Overview

**BaseDownloader** is a Jakarta EE web application running on IBM Liberty. It provides both a synchronous single-stream Base64 download endpoint (legacy) and a full asynchronous chunked Base64 download workflow accessible through an HTML browser UI.

**Stack**: Java 8, Maven, Jakarta EE (jakartaee-8.0), IBM Liberty, Eclipse MicroProfile 4.1

## Build & Test Commands

```bash
# Build WAR file (creates target/BaseDownloader-1.0.0.war)
mvn clean package

# Start Liberty server with application deployed
mvn liberty:run

# Stop the running Liberty server
mvn liberty:stop

# Runs tests (no tests currently defined in project)
mvn test
```

**Key**: The project builds a WAR file named `base-downloader` deployed to Liberty at context root `/BaseDownloader`.

## Code Organization & Architecture

### Package Layout

```
edu.java.application   — JAX-RS entry point (Application.java) and all string/int constants (Constants.java)
edu.java.rest          — REST controllers and REST-layer constants (ApiConstants.java)
edu.java.security      — Authentication filter, credential store, request context
edu.java.service       — Domain model (DownloadTask, DownloadChunk), services, schedulers
edu.java.util          — (reserved for future utilities)
```

### Entry Points

- **REST Application**: [`src/main/java/edu/java/application/Application.java`](src/main/java/edu/java/application/Application.java) — Declares OpenAPI metadata and the two global security schemes (Basic and Bearer). Mounted at `@ApplicationPath("api")`.
- **Controllers**:
  - [`LoginController`](src/main/java/edu/java/rest/LoginController.java) at `/api/login` — HTML login form, session establishment, and logout.
  - [`DownloadAsyncController`](src/main/java/edu/java/rest/DownloadAsyncController.java) at `/api/asyncdownload` — Primary async chunked download workflow (submit, status, chunk download, list).
  - [`DownloadController`](src/main/java/edu/java/rest/DownloadController.java) at `/api/download` — Legacy synchronous single-stream Base64 download.
  - [`InfoController`](src/main/java/edu/java/rest/InfoController.java) at `/api/info` — Health/readiness probe (unauthenticated).

### Constants — Two classes, split by concern

- [`edu.java.application.Constants`](src/main/java/edu/java/application/Constants.java) — **Application-wide**: MicroProfile Config property keys (prefixed `CONFIG_`), context-root and API path fragments, file-name conventions, application metadata (display name, version, GitHub URL). This is the single source of truth for all magic strings shared across more than one package.
- [`edu.java.rest.ApiConstants`](src/main/java/edu/java/rest/ApiConstants.java) — **REST-layer only**: HTTP header names (`X-BD-UUID`, `X-BD-CRC32`, `X-BD-MD5`, `X-BD-SHA256`, `X-BD-Message`), auth scheme literals, path segments, chunk size (`CHUNK_SIZE_BYTES = 1 << 18`, i.e. 256 KB of raw binary per chunk).

### Authentication & Security

Authentication is implemented as a **JAX-RS `ContainerRequestFilter`** ([`AuthFilter`](src/main/java/edu/java/security/AuthFilter.java)) running at `Priorities.AUTHENTICATION` — before any controller method is invoked.

**Two mechanisms are tried in order for every protected request:**
1. `Authorization` header — Basic (`username:password` Base64-encoded) or Bearer (raw token) validated against [`CredentialStore`](src/main/java/edu/java/security/CredentialStore.java).
2. HTTP session attribute `bd.authenticated.username` set by `POST /api/login`.

**Exempt paths** (no auth required): `/BaseDownloader/api/login`, `/BaseDownloader/api/login/logout`, `/BaseDownloader/api/info`, `/health`, `/metrics`, `/openapi`, `/api/docs`, `/api/explorer`.

**Credential store** ([`CredentialStore`](src/main/java/edu/java/security/CredentialStore.java)): a `@Singleton` EJB that reads `src/main/liberty/config/bd-credentials.properties` at startup and reloads it every minute via `@Schedule`. Format: one line per user — `username=password:token`. The credential file path is configurable via the MicroProfile Config property `bd.credentials.file` (Liberty `<variable>`).

**Legacy fallback**: Basic `BD:1.0.0` and Bearer `BD:1.0.0` (or its Base64-encoding) are still accepted as a hardcoded PoC credential (defined in `Constants.APPLICATON` + `Constants.APP_VERSION`). A `WARN`-level log is emitted when this fallback is used. Remove it for production deployments.

**Brute-force mitigation**: Both `AuthFilter` and `LoginController` sleep `AuthFilter.BRUTE_FORCE_DELAY_MS` (30 000 ms) on the HTTP thread for every failed authentication attempt. This is intentional — do not remove it.

**Session management**: Sessions are created by `LoginController.submitLogin()` only after successful credential validation (prevents session-fixation). [`RequestContext`](src/main/java/edu/java/security/RequestContext.java) is a `@RequestScoped` CDI bean that carries the authenticated username for the duration of one HTTP request.

### Async Chunked Download Workflow

The main feature is the async chunked download at `/api/asyncdownload`:

1. `GET /api/asyncdownload` — HTML form for URL submission.
2. `POST /api/asyncdownload` — Validates URL, creates a [`DownloadTask`](src/main/java/edu/java/service/DownloadTask.java), registers it in [`DownloadTaskRegistry`](src/main/java/edu/java/service/DownloadTaskRegistry.java), fires [`ChunkedDownloadService.startDownload()`](src/main/java/edu/java/service/ChunkedDownloadService.java) asynchronously (`@Asynchronous`), returns HTTP 202 with the UUID.
3. `GET /api/asyncdownload/{uuid}` — Status page; auto-refreshes every 5 s while PENDING/IN_PROGRESS; shows chunk table with CRC32/MD5/SHA-256 and reassembly commands when DONE.
4. `GET /api/asyncdownload/{uuid}/{index}` — Serves chunk `index` (1-based) as a downloadable `text/plain` file with `X-BD-CRC32`, `X-BD-MD5`, and `X-BD-SHA256` response headers.
5. `GET /api/asyncdownload/list` — HTML table of all registered tasks.

**Task lifecycle states** (`DownloadTask.Status`): `PENDING` → `IN_PROGRESS` → `DONE` or `FAILED`.

**Chunk persistence**: [`ChunkStorageService`](src/main/java/edu/java/service/ChunkStorageService.java) writes each chunk to `{bd.chunk.dir}/{uuid}/{originalFileName}.{n}.txt` immediately on production. The `DownloadChunk` object retains only checksums and file coordinates; Base64 text is never kept in memory. `getBase64Content()` reads back from disk on demand.

**Expiry and cleanup**:
- Tasks expire 1 hour after submission (`DownloadTask.expiresAt`).
- [`DownloadCleanupScheduler`](src/main/java/edu/java/service/DownloadCleanupScheduler.java) fires every minute (`@Schedule`) to evict expired tasks from the registry and delete their on-disk chunk directories.
- [`StartupCleanupService`](src/main/java/edu/java/service/StartupCleanupService.java) (`@Singleton @Startup`) wipes the entire `bd.chunk.dir` tree on application startup, removing orphaned chunk directories from previous server runs (the in-memory registry does not survive a restart).

### HTML UI — `HtmlService`

[`HtmlService`](src/main/java/edu/java/service/HtmlService.java) (`@ApplicationScoped` CDI bean) generates all HTML pages. All CSS is inline in a single `<style>` block. JS is minimal — only used on the status page for clipboard copy buttons and opening all chunk tabs. Key methods:
- `page(title, bodyHtml, extraHeadHtml, username)` — full page with header, breadcrumb, body, footer.
- `errorPage(status, message, username)` — styled error page.
- `table(headers, rows)` — styled `<table>`.
- `statusBadge(status)` — coloured `<span>` for task status.
- `esc(s)` / `escAttr(s)` — static HTML-escape helpers; always use these for user-supplied or URL-derived strings placed in HTML.

### Legacy Synchronous Endpoint

[`DownloadController`](src/main/java/edu/java/rest/DownloadController.java) at `/api/download` delegates to [`StreamDownloadService`](src/main/java/edu/java/service/StreamDownloadService.java). It downloads the remote resource synchronously (blocking the HTTP thread), writes the raw binary to `{originalFileName}` and the Base64 output to `{originalFileName}.b64` in the current working directory (both always overwritten), and returns the Base64 as an HTML response. This is a PoC — concurrent requests race on the same files. Use the async chunked endpoint for new work.

## Code Style Guidelines

### Imports & Packages
- Use Jakarta EE imports (`javax.ws.rs.*`, `javax.ejb.*`, `javax.inject.*`, not `com.sun.*`)
- Organize imports: standard library, then `javax.*`, then `org.eclipse.microprofile.*`, then `edu.java.*`
- Application-wide constants → `edu.java.application.Constants`; REST-layer constants → `edu.java.rest.ApiConstants`

### Naming Conventions
- **Constants**: `ALL_CAPS` with `static final` (e.g. `CHUNK_SIZE_BYTES`, `HEADER_X_BD_UUID`)
- **Config-property key constants**: prefixed `CONFIG_` (e.g. `CONFIG_BD_CHUNK_DIR`)
- **Path-fragment constants**: prefixed `PATH_` (no leading/trailing slash)
- **Methods**: camelCase, verb-first (e.g. `startDownload()`, `validateTriple()`, `getBase64Content()`)
- **Classes**: PascalCase; suffix `Controller` for REST endpoints, `Service` for business logic, `Scheduler` for EJB timers, `Registry` for in-memory stores, `Util` for pure utilities

### Formatting & Comments
- Use `//@formatter:off` and `//@formatter:on` around complex annotation blocks — respect these directives, do not reformat inside them
- Javadoc (`/** */`) on all public classes and methods
- Single-line `//` comments for inline logic
- Keep methods focused on a single responsibility

### Exception Handling
- **Never silently ignore exceptions**: all catch blocks must log, rethrow, or handle explicitly
- Use `System.out.println(...)` for diagnostic output (project convention); SLF4J `logger` for structured log entries
- Catch `IOException` explicitly in stream/file operations; do not swallow lower-level exceptions without context
- Never expose stack traces to clients; return a generic `Response.status(Status.XXX)` instead

### Type Handling
- Prefer Java 8 API; the async workflow uses streams/lambdas in `StartupCleanupService` but the controllers avoid them
- Array copies: use `System.arraycopy()` for bulk byte-array operations
- Null-safe string comparison: use `.equals()` on the known-non-null side, never `==`; check for `null` before comparing

### REST/OpenAPI Annotations
- **Always document** endpoints with `@Operation`, `@APIResponses`, `@Parameter`, and `@Tag`
- Use `@ExampleObject` for real-world sample inputs in `@APIResponses`
- Declare `@SecurityRequirements` on every endpoint requiring auth, referencing `ApiConstants.SECURITY_SCHEME_BASIC` and `ApiConstants.SECURITY_SCHEME_BEARER`
- Controller EJB type: prefer `@Stateless` over `@Singleton` for controllers that hold no mutable state (avoids the default write-lock)

### Error Responses
- Return `Response.status(Status.XXX).build()` or `.entity(htmlService.errorPage(...))` — never throw exceptions out of controllers
- Include context in the `X-BD-Message` header (`ApiConstants.HEADER_X_BD_MESSAGE`) when returning auth failures
- For HTML-producing endpoints: use `htmlService.errorPage(status, message, username)` for error bodies
- For `text/plain` chunk endpoints: return a plain-text error string in the entity body

## Testing

**No tests currently exist** in the project. If tests are added:
- Use Maven Surefire plugin (add to `pom.xml` if needed)
- Place tests in `src/test/java` following the same package structure as source
- Test naming convention: `{ClassName}Test.java`
- Run single test: `mvn test -Dtest=ClassName`

## Liberty Server Configuration

Configuration is in [`src/main/liberty/config/server.xml`](src/main/liberty/config/server.xml). Key settings:
- HTTP endpoint: port `9080`, HTTPS: port `9443`, host `*`
- Application deployed at context root `BaseDownloader`
- Features enabled: `jakartaee-8.0`, `microProfile-4.1`, `adminCenter-1.0`, `restConnector-2.0`, `localConnector-1.0`
- Metrics: `/metrics` endpoint active (`mpMetrics authentication="false"`)
- Access logging enabled on the HTTP endpoint
- `<httpDispatcher welcomePageRedirectEnabled="true"/>` — redirects `http://localhost:9080/` to the application
- `<variable name="bd.chunk.dir" defaultValue="${java.io.tmpdir}/Base-Downloader"/>` — chunk storage directory; override without rebuilding
- `<variable name="bd.credentials.file" defaultValue="${server.config.dir}/bd-credentials.properties"/>` — credential file; default points to `src/main/liberty/config/bd-credentials.properties`

### Credential file (`bd-credentials.properties`)

Located at `src/main/liberty/config/bd-credentials.properties` by default. Format:
```properties
# username=password:token
admin=s3cr3t:mytoken123
```
Changes take effect within ~1 minute without a server restart.

## Known Gotchas

1. **Base64 Alignment**: The 3-byte alignment buffer in `ChunkedDownloadService.startDownload()` (and `StreamDownloadService`) is required for correct encoding. Only the largest multiple-of-3 prefix of accumulated bytes is encoded at each step; 0–2 remainder bytes are carried forward in `clipboardBuffer`. The `=` padding appears only once, at the very end of the final chunk. Do not modify this logic without understanding the requirement.

2. **Chunk persistence vs. in-memory content**: `DownloadChunk` does **not** retain Base64 text in memory after construction — it is written to disk immediately and read back on demand. Do not try to cache or store the content string outside the file system.

3. **Task registry is in-memory only**: `DownloadTaskRegistry` uses a `ConcurrentHashMap` with no persistence. On server restart the registry is empty. `StartupCleanupService` wipes the chunk directory on startup precisely because all on-disk directories become orphans after a restart.

4. **Legacy file overwrite**: `StreamDownloadService` writes `{originalFileName}` and `{originalFileName}.b64` to the working directory, always overwriting. Concurrent legacy downloads race on the same files. This is a known PoC limitation; use the async endpoint for all new work.

5. **Brute-force delay occupies an HTTP thread**: The 30-second sleep in `AuthFilter` and `LoginController` runs on the HTTP thread serving the failed request. This is intentional. In production with high concurrency, consider offloading the delay to an async thread pool.

6. **Legacy credential fallback**: `CredentialStore` still accepts `BD:1.0.0` as a hardcoded PoC credential (Basic and Bearer). A `WARN` is logged. Remove this fallback for production deployments.

7. **Formatter Directives**: The code uses `//@formatter:off/on` extensively around OpenAPI annotation blocks. Respect these directives; do not reformat the enclosed code.

8. **`@Singleton` vs `@Stateless`**: Controllers (`DownloadAsyncController`, `DownloadController`, `LoginController`, `InfoController`) are `@Stateless`. Schedulers (`DownloadCleanupScheduler`, `CredentialStore`) are `@Singleton` because `@Schedule` is only valid on singletons. `DownloadTaskRegistry` and `ChunkStorageService` and `HtmlService` are CDI `@ApplicationScoped`. Do not change these scopes without understanding the EJB locking implications.
