# BaseDownloader — To-Do List

Tasks are marked **☐ open** or **☑ done**.

Package conventions used throughout:
- `edu.java.rest` — JAX-RS controllers only (no business logic)
- `edu.java.service` — business logic, domain model, registry, scheduler

EJB annotation conventions used throughout:
- Controllers (`edu.java.rest`): `@Stateless` — the container maintains a pool of instances so
  concurrent requests are dispatched to different pool members simultaneously. Controllers hold
  no mutable instance state, so no locking is required and `@Singleton` (which serialises all
  method calls under its default write-lock) would be counterproductive.
- Stateless services (`edu.java.service`): `@Stateless` for the same reason.
- Scheduler (`edu.java.service.DownloadCleanupScheduler`): **`@Singleton`** — the EJB timer
  service requires a singleton bean; `@Schedule` is not valid on a `@Stateless` bean.
- Registry (`edu.java.service.DownloadTaskRegistry`): `@ApplicationScoped` CDI — one shared
  instance backed by a `ConcurrentHashMap`; thread-safety comes from the map, not a lock.

All new and touched classes must carry meaningful Javadoc on the class itself and on any method
that expresses business logic, a non-obvious assumption, or a lifecycle constraint. Plain
setters/getters do not need Javadoc. All REST endpoints must carry full MicroProfile OpenAPI
annotations (`@Tag`, `@Operation`, `@APIResponses`, `@Parameter`, `@SecurityRequirements`).

---

## Task 0 — Refactor existing business logic out of `edu.java.rest` ☐

**What to implement:**

The existing `DownloadController` mixes HTTP concerns with two pieces of reusable business logic
that new code will also need: authentication validation and stream downloading/Base64-encoding.
Both must be extracted into the new `edu.java.service` package so controllers stay thin.
The three existing controllers (`DownloadController`, `InfoController`, `LoginController`) must
also have their EJB annotation changed from `@Singleton` to `@Stateless` (see EJB annotation
conventions above).

1. **Change `@Singleton` → `@Stateless` on all existing controllers.**
   Replace `import javax.ejb.Singleton` with `import javax.ejb.Stateless` and the class-level
   annotation in `DownloadController`, `InfoController`, and `LoginController`.
   Javadoc on each controller class must note that `@Stateless` is used (not `@Singleton`) so
   the EJB container can serve concurrent requests from a pool of instances without serialising
   access via the default write-lock that `@Singleton` would impose.

2. Create `edu.java.service.AuthService` as an `@ApplicationScoped` CDI bean:
   - Move the `authenticate(String authString)` and `isUserAuthenticated(String authString, String apiKeyAndPassword)`
     logic out of `DownloadController` verbatim into `AuthService`.
   - Expose a single public method `Response enforceAuth(String authString, String apikey)` that
     replicates the current two-branch check (`apikey` first, then `authString`) and returns a
     non-null `Response` (401) when the caller is not authenticated, or `null` when authentication
     succeeds.
   - Javadoc must explain: the hardcoded credential format (`BD:1.0.0` Base64-encoded), the
     deliberate 30-second sleep on auth failure as a brute-force mitigation, and that both Basic
     and Bearer schemes are supported.

3. Create `edu.java.service.StreamDownloadService` as a `@Stateless` EJB:
   - Move the `downloadStream(URL urlOfResource, String fileName)` method from `DownloadController`
     into `StreamDownloadService`, keeping the existing 1 KB read-loop, 3-byte-aligned Base64
     clipboard logic intact.
   - **Replace the two hardcoded filename constants** (`FILE_NAME_ZIP = "test.zip"` and
     `FILE_NAME_BASE64 = "test.b64"`) with filenames derived from the `fileName` parameter that
     is already passed into `downloadStream()`. The binary output file becomes `{fileName}` and
     the Base64 output file becomes `{fileName}.b64`. The `fileName` value comes from
     `new File(urlOfResource.getPath()).getName()` in `DownloadController.base64Download()`,
     which extracts the last path segment of the URL (e.g. `data.zip` from
     `https://example.com/files/data.zip`) — the same convention used by `DownloadTask`.
   - Remove the two `FILE_NAME_ZIP` / `FILE_NAME_BASE64` constants from `DownloadController`
     entirely; they must not survive the refactor.
   - Javadoc must explain the 3-byte alignment requirement for correct Base64 encoding, the
     clipboard buffer role (holding remainder bytes between loop iterations), that the two output
     files use the URL-derived filename (not a hardcoded name), and that they are always
     overwritten — the concurrency race on the files is a known PoC limitation documented in
     Gotcha 4; `@Stateless` does not fix that, and Gotcha 4 is resolved only when the
     `ChunkedDownloadService` (Task 2) is used instead.

4. Update `DownloadController` to:
   - Change annotation to `@Stateless` (step 1 above).
   - Inject `AuthService` via `@Inject` and replace its own `authenticate`/`isUserAuthenticated`
     calls with `AuthService.enforceAuth(authString, apikey)`.
   - Inject `StreamDownloadService` via `@Inject` and replace its own `downloadStream` call.
   - Remove the now-deleted private methods and the two filename constants; the controller body
     should shrink to only HTTP glue code.
   - Ensure all Javadoc on the controller class and `base64Download` method is updated to
     reflect the delegation.

5. Move `JsonbUtil` from `edu.java.rest` to `edu.java.service` (its package declaration should
   become `edu.java.service`). Update all existing `import` statements that reference it.

After this task `edu.java.rest` contains only: `Application`, `ApiConstants`, `DownloadController`,
`InfoController`, `LoginController` — all annotated `@Stateless`.

---

## Task 1 — `DownloadTask` domain model and `DownloadTaskRegistry` ☐

**What to implement:**

Create `edu.java.service.DownloadTask` — a plain Java class (not an EJB or CDI bean) holding
all state for one asynchronous download request:

- `String uuid` — unique identifier assigned at construction via `UUID.randomUUID().toString()`
- `String requestedUrl` — the URL string the user submitted (http or ftp)
- `String originalFileName` — filename extracted from the URL path (e.g. `data.zip`)
- `List<String> chunks` — ordered list of Base64-encoded text chunks built up by the background
  download; each chunk corresponds to exactly `ApiConstants.CHUNK_SIZE_BYTES` of original binary
  data (except the last chunk which may be smaller)
- `int totalChunks` — set to `-1` while the download is still running; set to
  `chunks.size()` when the download completes or fails
- `DownloadTask.Status status` — inner enum with values `PENDING`, `IN_PROGRESS`, `DONE`, `FAILED`
- `String errorMessage` — `null` unless `status == FAILED`; holds the exception message
- `Instant submittedAt` — set at construction to `Instant.now()`
- `Instant expiresAt` — set at construction to `submittedAt.plus(1, ChronoUnit.HOURS)`; used by
  the cleanup scheduler to decide when to discard the task and free memory

Javadoc on the class must explain the chunking concept: why the data is split (corporate
firewalls often block binary downloads but allow plain-text responses; Base64-encoded chunks are
plain text), how `expiresAt` interacts with the scheduler, and why `chunks` is a `List` (ordered
reassembly is required on the client side).

Create `edu.java.service.DownloadTaskRegistry` as a `@ApplicationScoped` CDI bean:

- Backing store: `ConcurrentHashMap<String, DownloadTask>` (thread-safe without explicit locks)
- Public methods: `register(DownloadTask task)`, `get(String uuid)`, `Collection<DownloadTask> getAll()`,
  `remove(String uuid)`, `removeExpired()` (removes all entries whose `expiresAt` is before `Instant.now()`)
- Javadoc on `removeExpired()` must explain that it is called by the scheduler and that removal
  also releases the Base64 chunk strings from memory.

Add constant `CHUNK_SIZE_BYTES = 1_048_576` (1 MB) to `ApiConstants` with a Javadoc comment
explaining that this is the number of **original binary bytes** accumulated before a chunk
boundary is created; the resulting Base64 text will be approximately 37 % larger (~1.37 MB).

---

## Task 2 — Background async chunked download service ☐

**What to implement:**

Create `edu.java.service.ChunkedDownloadService` as a `@Stateless` EJB:

1. Inject `DownloadTaskRegistry` via `@Inject`.

2. Expose a public `@Asynchronous` method `startDownload(DownloadTask task)`:
   - Set `task.status = IN_PROGRESS`.
   - Open a `BufferedInputStream` from `task.requestedUrl` using `new URL(task.requestedUrl).openStream()`.
     The URL constructor accepts both `http://` and `ftp://` schemes natively in Java 8.
   - Read the stream in 1 KB increments (same constant `BUFFER_LENGTH_STREAM = 1024` as the
     existing PoC code in `StreamDownloadService`).
   - Accumulate raw bytes into a growing chunk buffer. When the accumulated byte count reaches
     `ApiConstants.CHUNK_SIZE_BYTES` (1 MB), apply the 3-byte-aligned Base64 clipboard logic
     (same algorithm as `StreamDownloadService.downloadStream()`) to encode exactly that 1 MB
     block, append the resulting Base64 string to `task.chunks`, and reset the chunk buffer.
   - After the stream is exhausted, encode any remaining bytes (the final, possibly smaller chunk)
     and append it to `task.chunks`.
   - Set `task.totalChunks = task.chunks.size()` and `task.status = DONE`.
   - On any `Exception`, set `task.status = FAILED` and `task.errorMessage = e.getMessage()`.
   - Do **not** write any files to disk; the chunks live entirely in memory inside the `DownloadTask`.

3. Javadoc on the class must explain:
   - Why `@Asynchronous` is used (the EJB container runs the method in a managed thread, so the
     calling HTTP request thread returns immediately with the UUID).
   - The 3-byte alignment rule for Base64 (every Base64 block must be a multiple of 3 raw bytes
     to avoid spurious `=` padding in the middle of a multi-chunk sequence that would break
     `certutil -decode` / `base64 -d` reassembly).
   - Why chunks are kept in memory rather than on disk (simplicity; the scheduler cleans them up).

---

## Task 3 — Submit form endpoint (`GET /api/download`) and submit action (`POST /api/download`) ☐

**What to implement:**

Create `edu.java.rest.DownloadAsyncController` as a `@Stateless` JAX-RS controller mapped to
`@Path(ApiConstants.RESOURCE_API_DOWNLOAD)`. Add `RESOURCE_API_DOWNLOAD = "download"` to
`ApiConstants`.

**Why two methods for submit (GET + POST)?**
The URL to download may be very long (deep FTP paths, HTTP URLs with query strings or tokens).
Passing a long URL as a `@QueryParam` embeds it in the request URL, which is subject to browser
and server URL-length limits (typically 2 000–8 192 characters). A `<textarea>` in an HTML form
submits its value in the HTTP request **body** (as `application/x-www-form-urlencoded`), bypassing
those limits entirely. Therefore the submit flow uses a two-step GET → POST pattern:

**GET `/api/download`** — `showSubmitForm` method:
- No auth required (the form itself is public; auth is enforced at submit time).
- Returns a `text/html` page containing:
  - A `<form method="POST" action="/base-downloader/api/download">` with:
    - A `<textarea name="url" rows="4">` for the download URL (multiline to comfortably fit long URLs)
    - A text `<input name="apikey">` for the API key
    - A submit button
  - Brief instructions ("Paste the http:// or ftp:// URL of the file you want to download…").
- Full OpenAPI annotation: `@Operation`, `@APIResponse(200)`, `@Tag`.

**POST `/api/download`** — `submitDownload` method:
- Consumes `application/x-www-form-urlencoded` (`@Consumes(MediaType.APPLICATION_FORM_URLENCODED)`).
- Accepts `@FormParam("url")` and `@FormParam("apikey")` (plus `@HeaderParam("Authorization")`
  for programmatic API callers who prefer HTTP headers over form fields).
- Validates that `url` is not blank; returns 400 Bad Request if it is.
- Calls `AuthService.enforceAuth(authString, apikey)`; returns its 401 response if non-null.
- Validates the URL is parseable as `java.net.URL` and that its protocol is `http`, `https`, or
  `ftp`; returns 400 with a descriptive message if not.
- Constructs a new `DownloadTask`, calls `DownloadTaskRegistry.register(task)`, calls
  `ChunkedDownloadService.startDownload(task)` (fires asynchronously and returns immediately).
- Returns HTTP 202 Accepted as a `text/html` page that shows:
  - The submitted URL and the assigned UUID.
  - A clickable link to `GET /api/download/{uuid}` so the user can check progress.
  - A note that the status page will refresh automatically (or must be reloaded manually).
- Also sets response header `X-BD-UUID` to the UUID.
- Full OpenAPI annotation with `@APIResponse(202)`, `@APIResponse(400)`, `@APIResponse(401)`,
  `@APIResponse(500)`.

---

## Task 4 — Status and chunk-list endpoint (`GET /api/download/{uuid}`) ☐

**What to implement:**

On `DownloadAsyncController`, add a `GET` method `getDownloadStatus` mapped to `@Path("{uuid}")`:

- Accepts `@PathParam("uuid")`, `@HeaderParam("Authorization")`, `@QueryParam("apikey")`.
- Calls `AuthService.enforceAuth`; returns 401 if not authenticated.
- Looks up the task in `DownloadTaskRegistry.get(uuid)`; returns 404 HTML page if not found.
- Returns a `text/html` summary page containing:
  - **Request details**: original URL, UUID, submitted-at timestamp, expires-at timestamp.
  - **Status section** (varies by `task.status`):
    - `PENDING` or `IN_PROGRESS`: a notice that the download is still running and the user should
      reload; show current chunk count so far as progress indication.
    - `DONE`: a numbered list of `<a href="/base-downloader/api/download/{uuid}/{n}">` links,
      one per chunk (n is 1-based for display). Each link's anchor text should read
      `{originalFileName}.{n}.txt`.
    - `FAILED`: the error message from `task.errorMessage`.
  - **Reassembly instructions** (only when `status == DONE`):
    - **Windows** (two commands, each in a `<pre>` block):
      1. `copy /b {name}.1.txt + {name}.2.txt + ... + {name}.N.txt {name}.txt`
      2. `certutil -decode {name}.txt {originalFileName}`
    - **Linux / macOS**:
      1. `cat {name}.1.txt {name}.2.txt ... {name}.N.txt > {name}.txt`
      2. `base64 -d {name}.txt > {originalFileName}`
  - **"Open all chunks" button** (only when `status == DONE`): embed the PoC JavaScript from
    `README.md` that calls `window.open()` for every `<a>` link on the page, wrapped in a
    `<button onclick="openLinks()">Open all chunk tabs</button>`. Browsers may block pop-ups;
    note this in a comment next to the button.
- Full OpenAPI annotation.

---

## Task 5 — Single-chunk download endpoint (`GET /api/download/{uuid}/{index}`) ☐

**What to implement:**

On `DownloadAsyncController`, add a `GET` method `getChunk` mapped to `@Path("{uuid}/{index}")`:

- Accepts `@PathParam("uuid")`, `@PathParam("index")` (1-based integer), auth params.
- Calls `AuthService.enforceAuth`; returns 401 if not authenticated.
- Looks up the task; returns 404 if not found.
- Converts `index` to 0-based (`index - 1`); returns 404 if `index < 1` or if
  `task.chunks.size() < index` (chunk not yet available or index out of range).
- Returns the Base64 text string as `text/plain` with response header:
  `Content-Disposition: attachment; filename="{originalFileName}.{index}.txt"`
  so the browser downloads it as a named file rather than displaying it inline.
- Full OpenAPI annotation including the 1-based index convention.

---

## Task 6 — List all tasks endpoint (`GET /api/download/list`) ☐

**What to implement:**

On `DownloadAsyncController`, add a `GET` method `listDownloads` mapped to `@Path("list")`:

- Requires auth.
- Iterates `DownloadTaskRegistry.getAll()` and returns a JSON array (via `JsonbUtil`) where
  each entry contains: `uuid`, `requestedUrl`, `originalFileName`, `status`,
  `chunksAvailable` (current `task.chunks.size()`), `totalChunks`, `submittedAt`, `expiresAt`.
- Returns `application/json`.
- Full OpenAPI annotation.

Note: this endpoint uses the sub-path `/list` rather than the bare `/download` root to avoid
a JAX-RS routing conflict with the `GET /api/download` form endpoint (Task 3).

---

## Task 7 — Scheduled cleanup of expired tasks ☐

**What to implement:**

Create `edu.java.service.DownloadCleanupScheduler` as a `@Singleton` EJB:

- Inject `DownloadTaskRegistry` via `@Inject`.
- Add a method `cleanupExpiredTasks` annotated with
  `@Schedule(hour = "*", minute = "*/1", second = "0", persistent = false)`.
  The `persistent = false` flag prevents Liberty from persisting timer state across restarts,
  which is correct for an ephemeral in-memory registry.
- The method calls `DownloadTaskRegistry.removeExpired()` and logs (via `System.out.println`,
  matching the existing code style) each removed task's UUID, URL, and expiry timestamp.
- Javadoc must explain: the 1-minute schedule is a trade-off between prompt cleanup (freeing
  memory) and overhead; `persistent = false` means timers are recreated on server restart and
  any tasks that were in `DownloadTaskRegistry` at shutdown are silently lost (acceptable for
  this use case since the registry is also in-memory).

---

## Task 8 — Wire-up and integration validation ☐

**What to implement:**

- Verify that `src/main/webapp/WEB-INF/beans.xml` exists with `bean-discovery-mode="all"` (or
  create it) so that `@ApplicationScoped` CDI beans in `edu.java.service` are discovered by
  Liberty's CDI container.
- Confirm `Application.java` has no `getClasses()` override; JAX-RS auto-scanning should pick up
  `DownloadAsyncController` automatically.
- Run `mvn clean package` and confirm the WAR builds without errors or warnings.
- Manually test the complete happy path:
  1. Open `GET /base-downloader/api/download` in a browser → submit form with a small test URL.
  2. Note the UUID in the 202 response; open the status link.
  3. Reload until status is `DONE`.
  4. Download chunk 1 (click link or use `curl`).
  5. If only one chunk: `certutil -decode {name}.1.txt {originalFileName}` (Windows) or
     `base64 -d {name}.1.txt > {originalFileName}` (Linux) and verify the file is intact.
  6. Test with a multi-chunk file (> 1 MB) to verify chunk boundaries and reassembly.
- Test FTP URL: use a publicly accessible FTP URL to confirm `ftp://` scheme is handled.
