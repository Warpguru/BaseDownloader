# BaseDownloader

A Jakarta EE REST web service that downloads remote resources (over HTTP, HTTPS, or FTP),
splits the binary content into fixed-size Base64-encoded chunks, and makes each chunk
available as a plain-text file download. This lets users in corporate environments — where
binary file downloads are blocked by firewalls — retrieve arbitrary files by downloading
plain-text chunks and reassembling them locally.

## Architecture

### Overview

BaseDownloader is deployed as a WAR to IBM Liberty at context root `/base-downloader`.
All REST endpoints are rooted at `/base-downloader/api`. Every HTML page is served by the
application itself (no external CSS, JavaScript frameworks, or CDN dependencies).

---

### Package Structure

| Package | Role |
|---|---|
| `edu.java.application` | `Application.java` (JAX-RS entry point, OpenAPI metadata) and `Constants.java` (all shared string/int constants) |
| `edu.java.rest` | JAX-RS controllers only — HTTP glue, no business logic |
| `edu.java.security` | `AuthFilter` (JAX-RS `ContainerRequestFilter`), `CredentialStore`, `RequestContext` |
| `edu.java.service` | Business logic: download, chunking, registry, scheduler, HTML generation |

> **Note:** The `edu.java.util` and `security` (root package) directories are empty —
> both were removed during the Task 12 security refactor.

---

### Component Diagram

```mermaid
graph TD
    subgraph Liberty["IBM Liberty — JAX-RS + EJB + CDI"]
        subgraph rest["edu.java.rest — Controllers"]
            LC["LoginController\n@Stateless\nGET /api/login\nPOST /api/login\nGET /api/login/logout"]
            IC["InfoController\n@Stateless\nGET /api/info"]
            DC["DownloadController\n@Stateless\nGET /api/base"]
            DAC["DownloadAsyncController\n@Stateless\nGET|POST /api/download\nGET /api/download/list\nGET /api/download/{uuid}\nGET /api/download/{uuid}/{n}"]
        end
        subgraph security["edu.java.security — Auth"]
            AF["AuthFilter\n@Provider @Priority(AUTHENTICATION)\nContainerRequestFilter"]
            CS["CredentialStore\n@Singleton EJB\n@Schedule reload every 1 min"]
            RC["RequestContext\n@RequestScoped CDI\nauthenticated username"]
        end
        subgraph service["edu.java.service — Business Logic"]
            SDS["StreamDownloadService\n@Stateless"]
            CDS["ChunkedDownloadService\n@Stateless + @Asynchronous"]
            CSS["ChunkStorageService\n@ApplicationScoped"]
            DTR["DownloadTaskRegistry\n@ApplicationScoped\nConcurrentHashMap"]
            DCS["DownloadCleanupScheduler\n@Singleton\n@Schedule every 1 min"]
            SCS["StartupCleanupService\n@Singleton @Startup\ndeletes stale chunk dirs"]
            DT["DownloadTask\nPOJO — uuid/url/chunks\nstatus/timestamps"]
            DC2["DownloadChunk\nPOJO — index/file path\nCRC32/MD5/SHA-256"]
            HS["HtmlService\n@ApplicationScoped\nall HTML generation"]
        end
        subgraph app["edu.java.application"]
            APP["Application.java\n@ApplicationPath JAX-RS\nOpenAPI metadata"]
            CON["Constants.java\nall shared constants"]
        end
    end

    AF -->|validateAuthorizationHeader| CS
    AF -->|setUsername| RC
    LC -->|validateTriple| CS
    DC -->|downloadStream| SDS
    DAC -->|register/get/getAll| DTR
    DAC -->|startDownload| CDS
    DAC -->|getTaskDirectory| CSS
    CDS -->|writeChunk| CSS
    CDS -->|writes chunks into| DT
    DT -->|contains| DC2
    DTR -->|stores| DT
    DCS -->|removeExpired| DTR
    SCS -->|deleteAllTaskDirectories| CSS
    LC & IC & DC & DAC -->|page/errorPage/table/statusBadge| HS
```

---

### Request Flows

#### Authentication — every protected request

```mermaid
sequenceDiagram
    actor User
    participant AF as AuthFilter
    participant CS as CredentialStore
    participant RC as RequestContext
    participant Ctrl as Any Controller

    User->>AF: any request to /api/*
    AF->>AF: check exempt paths\n(/api/login, /api/login/logout, /api/info, /health, /metrics, /openapi)
    alt exempt path
        AF-->>Ctrl: pass through
    else Authorization header present
        AF->>CS: validateAuthorizationHeader(Basic/Bearer)
        CS-->>AF: username or null
    else session cookie present
        AF->>AF: read session attribute bd.authenticated.username
    end
    alt authenticated
        AF->>RC: setUsername(username)
        AF-->>Ctrl: pass through
    else not authenticated
        AF->>AF: Thread.sleep(30 000 ms) — brute-force mitigation
        AF-->>User: 401 Unauthorized
    end
```

#### Browser login flow — `GET /api/login` → `POST /api/login`

```mermaid
sequenceDiagram
    actor User
    participant LC as LoginController
    participant CS as CredentialStore

    User->>LC: GET /api/login
    LC-->>User: 200 HTML login form

    User->>LC: POST /api/login (username, password, token)
    LC->>CS: validateTriple(username, password, token)
    alt valid credentials
        LC->>LC: invalidate old session (session-fixation prevention)
        LC->>LC: create new session — set bd.authenticated.username
        LC-->>User: 303 redirect → /api/download
    else invalid credentials
        LC->>LC: Thread.sleep(30 000 ms)
        LC-->>User: 401 HTML login form with error message
    end
```

#### Legacy synchronous download — `GET /api/base?url=…`

```mermaid
sequenceDiagram
    actor User
    participant AF as AuthFilter
    participant DC as DownloadController
    participant SDS as StreamDownloadService

    User->>AF: GET /api/base?url=https://...
    AF-->>DC: authenticated — pass through
    DC->>SDS: downloadStream(url, fileName)
    SDS-->>SDS: stream URL in 1 KB chunks\nBase64-encode with 3-byte alignment\nwrite fileName and fileName.b64 to disk
    SDS-->>DC: full Base64 string
    DC-->>User: 200 HTML page containing full Base64 text
```

> This is the original PoC endpoint, retained for backwards compatibility. The entire download
> runs synchronously on the HTTP request thread. Concurrent requests race on the output files
> (`{fileName}` and `{fileName}.b64`) — a known limitation.

#### Async chunked download — submit, poll, retrieve

```mermaid
sequenceDiagram
    actor User
    participant AF as AuthFilter
    participant DAC as DownloadAsyncController
    participant DTR as DownloadTaskRegistry
    participant CDS as ChunkedDownloadService
    participant CSS as ChunkStorageService
    participant DT as DownloadTask

    User->>AF: GET /api/download
    AF-->>DAC: authenticated
    DAC-->>User: 200 HTML submit form

    User->>AF: POST /api/download (url)
    AF-->>DAC: authenticated
    DAC->>DT: new DownloadTask(url)
    DAC->>DTR: register(task)
    DAC->>CDS: startDownload(task) — fires @Asynchronous
    DAC-->>User: 202 HTML — UUID + status link

    Note over CDS,DT: Background EJB thread
    CDS->>DT: status = IN_PROGRESS
    loop read 1 KB at a time
        CDS->>CSS: accumulate until CHUNK_SIZE_BYTES boundary
        CDS->>CSS: Base64-encode block, write chunk file to disk
        CDS->>DT: append DownloadChunk (path, CRC32, MD5, SHA-256)
    end
    CDS->>DT: status = DONE / totalChunks = N

    User->>AF: GET /api/download/{uuid}
    AF-->>DAC: authenticated
    DAC->>DTR: retrieve(uuid)
    DAC-->>User: 200 HTML — status badge, chunk table with checksums,\nreassembly commands, auto-refresh if still running

    User->>AF: GET /api/download/{uuid}/1
    AF-->>DAC: authenticated
    DAC->>DT: getDownloadChunk(0)
    DAC-->>User: 200 text/plain — Content-Disposition: filename.1.txt\n+ X-BD-CRC32 / X-BD-MD5 / X-BD-SHA256 headers
```

#### Scheduled cleanup

```mermaid
sequenceDiagram
    participant SCH as DownloadCleanupScheduler
    participant DTR as DownloadTaskRegistry
    participant CSS as ChunkStorageService

    Note over SCH: @Schedule fires every 1 minute
    SCH->>DTR: removeExpired()
    DTR->>CSS: deleteTaskDirectory(uuid) for each expired task
    DTR-->>SCH: expired tasks removed
```

#### Startup cleanup

```mermaid
sequenceDiagram
    participant SCS as StartupCleanupService
    participant CSS as ChunkStorageService

    Note over SCS: @Singleton @Startup @PostConstruct\nfires once on application start
    SCS->>CSS: deleteAllTaskDirectories()
    Note over CSS: removes all UUID subdirectories under bd.chunk.dir\n(in-memory registry is empty on restart,\nso every existing directory is an orphan)
```

---

### Key Design Decisions

| Decision | Rationale |
|---|---|
| `ContainerRequestFilter` for auth (`AuthFilter`) | Centralises authentication in a single JAX-RS filter at `Priorities.AUTHENTICATION`. Controllers contain no credential-checking code and need not be modified when auth changes. |
| File-based credential store (`CredentialStore`) | Credentials live in `${server.config.dir}/bd-credentials.properties`, outside the WAR. Operators can add/change credentials without redeployment. The store reloads every minute. |
| Session + header dual auth | Browser users log in via the HTML form and receive an HTTP session cookie. Programmatic callers (scripts, OpenAPI UI) supply a `Basic` or `Bearer` `Authorization` header. Both paths are handled by `AuthFilter`. |
| 30-second delay on auth failure | Applied in both `AuthFilter` (header/session path) and `LoginController` (form path). Occupies one Liberty HTTP thread per failed attempt, making high-frequency credential guessing impractical. |
| Chunks written to disk (`ChunkStorageService`) | Allows chunks to survive a JVM garbage-collection cycle on large downloads. Each task gets a UUID-named subdirectory under `bd.chunk.dir` (`${java.io.tmpdir}/Base-Downloader` by default). |
| Per-chunk checksums (CRC32, MD5, SHA-256) | Computed during download so the status page can show a verification table and the browser can verify each downloaded chunk without re-requesting it. |
| Controllers use `@Stateless` not `@Singleton` | `@Singleton` EJBs serialise all method calls via a default write-lock. `@Stateless` uses a container-managed pool, enabling true concurrency. |
| `ChunkedDownloadService` uses `@Asynchronous` | The HTTP request thread returns the UUID immediately; the download runs in a separate EJB-managed thread. |
| Base64 chunk boundaries aligned to 3 bytes | Base64 encodes 3 raw bytes as 4 characters. Splitting at a non-multiple of 3 produces spurious `=` padding mid-stream, breaking `certutil -decode` and `base64 -d` on reassembly. |
| Submit URL via `POST` with `<textarea>` | Long URLs (deep FTP paths, query strings) can exceed browser/server URL-length limits (~8 KB) if passed as a query parameter. A form POST body has no practical length limit. |
| `HtmlService` owns all HTML generation | Every page uses a shared inline `<style>` block — no external CSS, no CDN. The appearance is fully self-contained in the WAR and works offline. Controllers call `HtmlService.page()` / `errorPage()` / `table()` / `statusBadge()` and contain only routing and data-fetch logic. |

---

### REST API Summary

| Method | Path | Auth required | Description |
|---|---|---|---|
| `GET` | `/` | — | Redirects to `/base-downloader/` (Liberty `httpDispatcher`) |
| `GET` | `/base-downloader/` | — | Redirects to `/api/login` (welcome-file `index.html`) |
| `GET` | `/api/info` | — | Health check — returns plain-text `OK` |
| `GET` | `/api/login` | — | HTML login form |
| `POST` | `/api/login` | — | Validate username + password + token; creates session on success |
| `GET` | `/api/login/logout` | — | Invalidates the current session; returns logout confirmation page |
| `GET` | `/api/base?url=…` | ✓ | Legacy sync download — returns full Base64 HTML |
| `GET` | `/api/download` | ✓ | HTML URL submission form |
| `POST` | `/api/download` | ✓ | Submit async download; returns 202 + UUID |
| `GET` | `/api/download/list` | ✓ | HTML list of all active download tasks |
| `GET` | `/api/download/{uuid}` | ✓ | HTML status page — chunk table, checksums, reassembly commands |
| `GET` | `/api/download/{uuid}/{n}` | ✓ | Download chunk `n` (1-based) as a `.txt` attachment |

OpenAPI / Swagger UI: `http://localhost:9080/openapi/ui`

---

### Authentication

#### Browser login
Navigate to `http://localhost:9080/base-downloader/api/login`. Enter your username, password,
and token. On success you are redirected to the download form. To log out, visit
`/api/login/logout`.

#### Programmatic access (scripts, API clients)
Supply an `Authorization` header on every request:

```
Authorization: Basic <base64(username:password)>
```
or
```
Authorization: Bearer <token>
```

where `<token>` is the token value from the credential file (not the password).

#### Credential file

Location: `${server.config.dir}/bd-credentials.properties`
(default: Liberty's `wlp/usr/servers/<serverName>/bd-credentials.properties`)

```properties
# Format: username=password:token
# Lines starting with # and blank lines are ignored.
# Changes are picked up within ~1 minute — no server restart needed.

admin=s3cr3t:mytoken123
```

The file is outside the WAR and survives redeployment. Override the path in `server.xml`:
```xml
<variable name="bd.credentials.file" value="/secure/path/bd-credentials.properties"/>
```

---

### Client-Side Reassembly

After downloading all chunks (`{name}.1.txt`, `{name}.2.txt`, …, `{name}.N.txt`) — the
exact commands are also shown on the status page with one-click copy buttons:

**Windows**
```bat
copy /b {name}.1.txt + {name}.2.txt + ... + {name}.N.txt {name}.txt
certutil -decode {name}.txt {originalFileName}
```

**Linux / macOS**
```bash
cat {name}.1.txt {name}.2.txt ... {name}.N.txt > {name}.txt
base64 -d {name}.txt > {originalFileName}
```

Chunk integrity can be verified before reassembly using the SHA-256, MD5, and CRC32 checksums
shown in the status-page chunk table and returned as `X-BD-SHA256`, `X-BD-MD5`, and
`X-BD-CRC32` response headers on each chunk download.

---

### Configuration Reference

All configuration is in `src/main/liberty/config/server.xml`.

| Liberty variable | Default value | Description |
|---|---|---|
| `bd.chunk.dir` | `${java.io.tmpdir}/Base-Downloader` | Root directory for on-disk chunk storage |
| `bd.credentials.file` | `${server.config.dir}/bd-credentials.properties` | Path to the credential properties file |

---

### Build & Run

```bash
# Build WAR (creates target/base-downloader.war)
mvn clean package

# Start Liberty with the application deployed
mvn liberty:run

# Package only — use when Liberty is already running (avoids clean)
mvn package -DskipTests
```

Application URL: `http://localhost:9080/base-downloader/`
