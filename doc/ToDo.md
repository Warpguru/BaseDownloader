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

## Task 0 — Refactor existing business logic out of `edu.java.rest` ☑

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

## Task 1 — `DownloadTask` domain model and `DownloadTaskRegistry` ☑

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

## Task 2 — Background async chunked download service ☑

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

## Task 3 — Submit form endpoint (`GET /api/download`) and submit action (`POST /api/download`) ☑

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

## Task 4 — Status and chunk-list endpoint (`GET /api/download/{uuid}`) ☑

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

## Task 5 — Single-chunk download endpoint (`GET /api/download/{uuid}/{index}`) ☑

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

## Task 6 — List all tasks endpoint (`GET /api/download/list`) ☑

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

## Task 7 — Scheduled cleanup of expired tasks ☑

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

## Task 8 — Wire-up and integration validation ☑

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

---

## Task 9 — Per-chunk checksums (CRC32 and MD5) ☑

**What to implement:**

Currently `DownloadTask.chunks` is a `List<String>` where each entry is the raw Base64 text.
A chunk must now carry two checksums alongside its content so the user can verify integrity
after downloading. Replace the bare `String` with a new value object.

1. Create `edu.java.service.DownloadChunk` — a plain immutable Java class with three fields:
   - `String base64Content` — the Base64-encoded text of this chunk (previously stored directly
     in `DownloadTask.chunks`)
   - `String crc32Hex` — CRC32 checksum of `base64Content` (the encoded text, not the raw bytes)
     expressed as an 8-character zero-padded lowercase hex string (e.g. `"0a3f7c21"`).
     Rationale: the user downloads the Base64 text file and can verify it with any CRC32 tool
     before attempting reassembly. `java.util.zip.CRC32` is used; no external dependency needed.
   - `String md5Hex` — MD5 checksum of `base64Content` expressed as a 32-character lowercase hex
     string. `java.security.MessageDigest` with algorithm `"MD5"` is used. This allows the user
     to run `md5sum {name}.N.txt` (Linux) or `certutil -hashfile {name}.N.txt MD5` (Windows) on
     the downloaded chunk file to verify it matches.
   - A constructor `DownloadChunk(String base64Content)` that computes both checksums immediately
     at construction time and stores them.
   - Javadoc must explain: why the checksum covers the Base64 text (not the decoded bytes) —
     because the user verifies the file they actually downloaded before decoding; and why both
     CRC32 and MD5 are provided — CRC32 is fast and available in every OS tool, MD5 is the
     standard for file integrity in Linux (`md5sum`) and Windows (`certutil`).

2. Update `DownloadTask`:
   - Change `List<String> chunks` to `List<DownloadChunk> chunks`.
   - Update `add(String chunk)` to `add(DownloadChunk chunk)`, or keep the `String` overload and
     add a new `add(DownloadChunk chunk)` — whichever is cleaner given the existing call sites.
   - Update `getChunk(int index)` to return `DownloadChunk` (or `null`).
   - `getNumberOfChunks()` and `setNumberOfTotalChunks()` are unaffected.

3. Update `ChunkedDownloadService.startDownload()`:
   - Wherever `downloadTask.add(Base64.getEncoder().encodeToString(...))` is called, wrap the
     result in `new DownloadChunk(encodedString)` before passing to `add()`.

4. Update `DownloadAsyncController`:
   - **Status page (`getDownloadStatus`)**: for each chunk link, append the CRC32 and MD5 values
     next to the link so the user can note them before downloading:
     `<a href="...">data.zip.1.txt</a> CRC32: 0a3f7c21 | MD5: d41d8cd98f00b204e9800998ecf8427e`
   - **Chunk download (`getChunk`)**: add response headers
     `X-BD-CRC32: {crc32Hex}` and `X-BD-MD5: {md5Hex}` so programmatic callers can verify
     the downloaded chunk without parsing the HTML status page.
   - **List endpoint (`listDownloads`)**: the JSON per-task summary does not need to enumerate
     individual chunk checksums (that would be verbose); no change needed there.

5. Update `ApiConstants`: add constants
   `HEADER_X_BD_CRC32 = "X-BD-CRC32"` and `HEADER_X_BD_MD5 = "X-BD-MD5"`.

---

## Task 10 — Filesystem persistence of Base64 chunks ☑

**What to implement:**

Chunks are currently held entirely in memory as `String` fields inside `DownloadTask`. For large
downloads (e.g. an OpenLiberty server ZIP at several hundred MB, whose Base64 representation is
~37 % larger still) this would exhaust the JVM heap. Chunks must instead be written to disk as
soon as they are produced, and served from disk on demand.

### 10.1 — Configuration: chunk storage directory

1. Add a Liberty `<variable>` element to `src/main/liberty/config/server.xml`:
   ```xml
   <variable name="bd.chunk.dir" defaultValue="${java.io.tmpdir}/Base-Downloader" />
   ```
   The `defaultValue` attribute means the variable is optional: if an operator wants to override
   the location they add `<variable name="bd.chunk.dir" value="/data/bd-chunks"/>` to their
   `server.xml`. The `${java.io.tmpdir}` expression is expanded by Liberty at startup to the
   JVM temporary directory.

2. Add a MicroProfile Config property source so the value is injectable in Java. In
   `src/main/liberty/config/server.xml` Liberty exposes its `<variable>` elements as MicroProfile
   Config properties automatically when the `microProfile-4.1` feature is active. Therefore no
   additional configuration file is needed.

3. Create `edu.java.service.ChunkStorageService` as an `@ApplicationScoped` CDI bean:
   - Inject the config value: `@Inject @ConfigProperty(name = "bd.chunk.dir") String chunkBaseDir`.
   - Expose:
     - `Path resolveChunkFile(String uuid, int chunkIndex, String originalFileName)` — returns
       `{chunkBaseDir}/{uuid}/{originalFileName}.{chunkIndex}.txt` (e.g.
       `Base-Downloader/550e8400-.../data.zip.1.txt`). The filename on disk is intentionally
       identical to the `Content-Disposition` filename the browser saves when the user clicks a
       chunk link, so the filesystem mirrors 1:1 what is shown on the status page. No zero-
       padding is used: the application always reads chunks by computed path, never by iterating
       directory entries, so filesystem sort order is irrelevant.
     - `void writeChunk(String uuid, int chunkIndex, String originalFileName, String base64Content)`
       — creates the directory `{chunkBaseDir}/{uuid}/` if it does not exist, writes
       `base64Content` as UTF-8 text to the chunk file, and returns.
     - `String readChunk(String uuid, int chunkIndex, String originalFileName)` — reads and
       returns the UTF-8 text from the chunk file, or throws `IOException` if the file does not
       exist.
     - `void deleteTaskDirectory(String uuid)` — deletes the directory
       `{chunkBaseDir}/{uuid}/` and all its contents recursively.
     - `void deleteAllTaskDirectories()` — deletes the entire `{chunkBaseDir}/` tree and
       recreates an empty `{chunkBaseDir}/` directory. Used at startup (Task 11).
   - Javadoc must explain: why the disk filename mirrors the user-visible name (1:1
     correspondence between status-page links and on-disk files aids debugging and manual
     recovery); why the base directory is configurable (the Liberty working directory may be on
     a small or read-only partition in production); why UTF-8 is used for writing (Base64 is
     pure ASCII, a strict subset of UTF-8, so any text-aware tool can open the file).

### 10.2 — Update `DownloadChunk` (from Task 9)

`DownloadChunk` no longer stores `base64Content` as an in-memory `String`. Instead it stores:
- `String uuid` — the owning task's UUID (needed to locate the file)
- `int chunkIndex` — 1-based index (needed to locate the file; matches the user-visible index
  and the on-disk filename)
- `String originalFileName` — the owning task's original filename (needed to locate the file)
- `String crc32Hex` — computed at write time (same as Task 9)
- `String md5Hex` — computed at write time (same as Task 9)
- Remove `base64Content` field; `getBase64Content()` must now read from disk via
  `ChunkStorageService.readChunk(uuid, chunkIndex, originalFileName)`.

The constructor signature becomes
`DownloadChunk(String uuid, int chunkIndex, String originalFileName, String base64Content, ChunkStorageService storage)`:
it computes the checksums from `base64Content`, calls
`storage.writeChunk(uuid, chunkIndex, originalFileName, base64Content)`, and stores only the
metadata fields. `getBase64Content()` calls
`storage.readChunk(uuid, chunkIndex, originalFileName)`.

Javadoc must explain why `base64Content` is not retained in memory after construction: for a
100 MB binary the Base64 text is ~137 MB; with multiple concurrent downloads the JVM heap would
be exhausted. The disk acts as a cheap, unbounded buffer.

### 10.3 — Update `ChunkedDownloadService`

- Inject `ChunkStorageService` via `@Inject`.
- Pass `ChunkStorageService` to each `new DownloadChunk(...)` constructor call so the chunk can
  persist itself immediately.
- Track the 0-based chunk index explicitly (currently implicit from `chunks.size()`) to pass to
  the `DownloadChunk` constructor.
- The existing 3-byte-aligned Base64 encoding logic is unchanged.
- Javadoc update: note that chunks are now written to `{bd.chunk.dir}/{uuid}/{index:04d}` and
  that memory usage is bounded regardless of file size.

### 10.4 — Update `DownloadAsyncController` (chunk download endpoint)

- Inject `ChunkStorageService` via `@Inject`.
- In `getChunk()`, replace direct access to `task.getChunk(index).base64Content` with
  `task.getChunk(index).getBase64Content()` (which now reads from disk via `ChunkStorageService`).
- Propagate `IOException` from `readChunk` as a 500 Internal Server Error with a descriptive
  message (do not expose the filesystem path in the response body).

### 10.5 — Update `DownloadTaskRegistry.removeExpired()`

- Inject `ChunkStorageService` via `@Inject`.
- After removing an expired task from the map, call
  `chunkStorageService.deleteTaskDirectory(task.getUuid())` so the chunk files are deleted at
  the same time the in-memory registry entry is evicted.
- Javadoc update: note that both the in-memory entry and the on-disk directory are removed
  together, and that a failure to delete the directory is logged but does not prevent the
  registry entry from being removed.

---

## Task 11 — Cleanup of persisted chunk directories on server restart ☑

**What to implement:**

When the Liberty server restarts (e.g. after a crash or a `mvn liberty:stop` + `mvn liberty:run`
cycle), any chunk directories written during the previous run remain on disk but the in-memory
`DownloadTaskRegistry` is empty. Without cleanup, those orphaned directories would accumulate
indefinitely on the server's filesystem.

### Analysis of startup hook options on Liberty

Three mechanisms can run code at application startup in a Jakarta EE / Liberty environment:

| Mechanism | How to use | When it fires | Suitable? |
|---|---|---|---|
| `@Singleton` EJB `@PostConstruct` | Annotate a method `@PostConstruct` on a `@Singleton` EJB | After the EJB container initialises the singleton, before the first business method call | ✅ Reliable on Liberty; EJBs are initialised before the first HTTP request is dispatched |
| `ServletContextListener.contextInitialized()` | Implement `javax.servlet.ServletContextListener`, annotate with `@WebListener` | When the web application is started, before any servlet or filter is initialised | ✅ Well-defined startup order; fires before any HTTP request |
| CDI `@ApplicationScoped` `@Observes @Initialized(ApplicationScoped.class)` | Observe the CDI application-scope initialisation event | When the CDI container starts the application scope | ✅ Works on Liberty with CDI 2.0; slightly less portable |

**Recommendation:** Use a `@Singleton` EJB `@PostConstruct`. Reasons:
- The EJB container on Liberty guarantees that all `@Singleton` beans are initialised eagerly
  when the application starts (Liberty initialises them before the first request).
- The `@PostConstruct` method can `@Inject` `ChunkStorageService` directly, keeping the
  implementation simple.
- The `@Schedule`-based `DownloadCleanupScheduler` is already a `@Singleton` EJB; adding a
  second `@Singleton` for startup cleanup is consistent.
- A `ServletContextListener` would also work but cannot `@Inject` CDI/EJB beans reliably in
  all Liberty versions without additional configuration.

### What to implement

Create `edu.java.service.StartupCleanupService` as a `@Singleton` EJB with
`@Startup` (forces eager initialisation) :

- Inject `ChunkStorageService` via `@Inject`.
- Add a `@PostConstruct` method `cleanupOnStartup()` that calls
  `chunkStorageService.deleteAllTaskDirectories()`.
- Log (via `System.out.println`) the path that was cleaned and how many top-level UUID
  directories were removed.
- Javadoc must explain: why `@Startup` is needed (without it Liberty may defer singleton
  initialisation until the first call, meaning the cleanup would only run when the first
  request arrives rather than at server start); why orphaned directories accumulate (the
  in-memory registry is lost on restart but disk files are not); and why the entire base
  directory is wiped rather than trying to match orphans against a registry (the registry is
  empty on startup, so every existing directory is by definition an orphan).

---

## Task 12 — Application security: login page with file-based credential store ☑

**What to implement:**

The `security` package contains a partial `HttpAuthenticationMechanism` skeleton that reads
`username`, `password`, and `token` from request parameters but always grants access (the
`validate()` method never checks the values against any store). This task replaces that
skeleton with a complete, consistent security implementation across the application.

### 12.1 — Analysis: available options (agent must present these and ask for a decision)

Before implementing, the agent must analyse the following three options in the context of this
Jakarta EE 8 / Liberty application, explain the trade-offs, and **ask the user which option to
implement** before writing any code.

**Option A — Custom `HttpAuthenticationMechanism` (extend the existing skeleton)**

Jakarta Security 1.0 (`javax.security.enterprise`) is already on the classpath via
`jakartaee-8.0`. The existing `AuthenticationMechanism` class already implements
`HttpAuthenticationMechanism`. The approach:
- Complete `validate()` to check the submitted triple against a credential file (see 12.2).
- Annotate with `@FormAuthenticationMechanismDefinition` or keep the custom `validateRequest`
  implementation (the custom approach is more flexible for the three-field form).
- Protect all REST paths under `/api/*` with `@RolesAllowed("User")` (or similar) on the
  controller classes, which causes the container to invoke `validateRequest` automatically.
- The login HTML form at `GET /api/login` becomes the entry point; the container redirects
  unauthenticated requests to it.
- **Pros**: fully standards-based; session management (cookie) is handled by the container;
  integrates with Liberty's `appSecurity-3.0` feature already included in `jakartaee-8.0`.
- **Cons**: requires configuring Liberty's security constraints (either via `web.xml` security
  constraints or programmatic `HttpMessageContext`); the three-field credential (username +
  password + token) is non-standard and needs a custom `IdentityStore`.

**Option B — `@FormAuthenticationMechanismDefinition` with a custom `IdentityStore`**

Use the built-in Jakarta Security form-login mechanism triggered by the
`@FormAuthenticationMechanismDefinition(loginPage="/login.html", errorPage="/login-error.html")`
annotation on the `Application` class. Implement a custom `IdentityStore` that:
- Reads credentials from the file (see 12.2).
- Validates the `UsernamePasswordCredential` submitted by the form.
- Note: the standard form mechanism submits only `j_username` and `j_password`; a token field
  cannot be added without a custom mechanism.
- **Pros**: the least code — the container handles the redirect, session cookie, and logout.
- **Cons**: the standard form supports only username + password, not the required three-field
  triple. Would require a hybrid that overrides credential extraction, making it as complex as
  Option A.

**Option C — Programmatic session-cookie auth (no container security integration)**

Skip `HttpAuthenticationMechanism` entirely. Implement login as a plain JAX-RS `POST /api/login`
endpoint that:
- Validates the submitted triple against the credential file.
- On success, creates an `HttpSession` (via injected `HttpServletRequest`) and stores the
  authenticated username in the session.
- Returns a redirect to the application root.
On every subsequent request, a JAX-RS `ContainerRequestFilter` checks for the session attribute
and returns 401 (after the 30-second sleep) if it is absent.
- **Pros**: simple; no container security configuration needed; full control over the 30-second
  sleep behaviour on all unauthenticated requests.
- **Cons**: re-implements what the container provides; session management is manual; CSRF
  protection must also be handled manually.

### 12.2 — Credential file format

Regardless of the chosen option, credentials are stored in a UTF-8 properties file located at
a path configured in `server.xml` as a Liberty variable:
```xml
<variable name="bd.credentials.file" defaultValue="${server.config.dir}/bd-credentials.properties" />
```
File format (one credential triple per line, `#` comments allowed):
```properties
# username=password:token
admin=s3cr3t:mytoken123
readonly=pass1:tok456
```
- `password` and `token` are stored in plaintext in this PoC. A Javadoc note must acknowledge
  this and recommend replacing with hashed values (e.g. BCrypt) in a production deployment.
- The file is read once at application startup and cached in memory (reloading requires a
  server restart). A `@PostConstruct` method in the credential-loading bean handles the
  initial read.
- Add constant `BD_CREDENTIALS_FILE_CONFIG_PROPERTY = "bd.credentials.file"` to `ApiConstants`.

### 12.3 — What to implement (after the user selects an option)

Whichever option is chosen, the following must be true across the entire application:

1. Accessing `GET /base-downloader/` (the context root) redirects to the login page.
2. All `/api/*` paths except `GET /api/info` (health check) require the user to be authenticated.
3. `GET /api/download` (the submit form — Task 3) must also require authentication, since it was
   previously public. Update the `showSubmitForm` method accordingly.
4. `AuthService.enforceAuth()` must be updated to check the container-managed session (or the
   session attribute, depending on the chosen option) in addition to (or instead of) the
   hardcoded `BD:1.0.0` credential. The hardcoded credential is removed. The deliberate
   30-second sleep on auth failure is retained.
5. The existing `security.Credentials`, `security.CredentialsCallerPrincipal`, and
   `security.AuthenticationMechanism` classes are refactored (not replaced from scratch) to
   incorporate the file-based credential store.
6. All new and modified classes carry Javadoc explaining the security model, the credential
   file location, and the deliberate brute-force mitigation (30-second sleep).
7. The `security` package is moved to `edu.java.security` to align with the project's package
   naming convention. Update all `import` statements that reference it.

---

## Task 13 — Consistent HTML look-and-feel via `HtmlService` ☐

**What to implement:**

Every HTML page currently produced by the application is assembled by inline string
concatenation inside the controller methods. Each page has a different visual structure, no
shared header or footer, and raw `border="1"` table attributes as the only styling. This task
introduces a dedicated `HtmlService` in `edu.java.service` that owns all HTML generation,
giving every page a consistent, professional appearance using only plain HTML and a small
inline `<style>` block — no external CSS files, no JavaScript frameworks, no build tools.
Controllers become pure routing + data-fetch code; they call `HtmlService` methods to obtain
finished HTML strings, following the MVC pattern.

### 13.1 — Create `edu.java.service.HtmlService`

Create `edu.java.service.HtmlService` as an `@ApplicationScoped` CDI bean.

**Page layout:** every page is structured as:

```
┌──────────────────────────────────────────────────────┐
│  HEADER: BaseDownloader vX.Y.Z  |  GitHub link       │
│          tagline                                      │
├──────────────────────────────────────────────────────┤
│  BREADCRUMB: Home > Downloads > <page title>         │
├──────────────────────────────────────────────────────┤
│  BODY: context-dependent content                     │
├──────────────────────────────────────────────────────┤
│  FOOTER: © BaseDownloader | OpenAPI UI link          │
└──────────────────────────────────────────────────────┘
```

**Styling rules (inline `<style>` in `<head>`, no external files):**
- Font: `font-family: Arial, Helvetica, sans-serif; font-size: 14px;` on `body`.
- Max page width: `max-width: 960px; margin: 0 auto; padding: 1em;` on a `<div class="page">`.
- Header: light blue background (`#e8f4f8`), `padding: 0.5em 1em`, horizontal rule below.
- Tables: `border-collapse: collapse` with `border: 1px solid #ccc` on `th` and `td`,
  `padding: 0.3em 0.6em`. Header row (`<thead>`) has background `#ddeeff`.
- `<pre>` blocks: `background: #f4f4f4; padding: 0.5em; border-left: 3px solid #0066cc`.
- Links: standard blue, no custom override.
- Status badges: `DONE` in green (`#006600`), `FAILED` in red (`#cc0000`),
  `IN_PROGRESS` / `PENDING` in orange (`#cc6600`) — rendered as `<span>` with inline colour.
- No JavaScript except the minimal `openLinks()` function already used on the status page
  (and only on that page, only when status is `DONE`). No jQuery, no Bootstrap, no external
  resources that would require an internet connection.

**Public methods on `HtmlService`:**

All methods take a `String pageTitle` and a `String bodyHtml` (pre-built body content) and
wrap them in the full page structure. Overloads or a builder pattern are acceptable.

- `String page(String pageTitle, String bodyHtml)` — standard page with header, breadcrumb
  derived from `pageTitle`, body, and footer.
- `String page(String pageTitle, String bodyHtml, String extraHeadHtml)` — same but inserts
  `extraHeadHtml` into `<head>` (used for the `openLinks()` script on the status page).
- `String errorPage(int httpStatus, String message)` — produces a minimal page with the HTTP
  status code and message in a styled error box; used for 400, 401, 404, 500 responses.
- `String table(String[] headers, List<String[]> rows)` — builds a styled `<table>` with
  `<thead>` and `<tbody>` from the given headers and row data arrays.
- `String statusBadge(DownloadTask.Status status)` — returns a `<span>` coloured according
  to the status value (green / red / orange as above).

The application name, version string, and GitHub URL must be constants in `ApiConstants`:
- `APP_DISPLAY_NAME = "BaseDownloader"`
- `APP_VERSION = "1.0.0"`
- `APP_GITHUB_URL = "https://github.com/RomanStangl/base-downloader"` (update to the real URL)

Javadoc on `HtmlService` must explain: why all styling is inline (no external files to deploy
or cache-bust; the entire appearance is self-contained in the WAR); why JavaScript is kept
minimal (the application targets Java developers who may inspect the page source; simple HTML
is more readable and auditable than a JS-heavy SPA); and why `HtmlService` is `@ApplicationScoped`
(it holds no mutable state — only constants and string-building logic — so a single shared
instance is sufficient).

### 13.2 — Migrate all controllers to use `HtmlService`

Update every controller method that currently returns `text/html` to:
1. Build the body content as a local `String` or `StringBuilder` (data-driven, no page chrome).
2. Call `HtmlService.page(...)` or `HtmlService.errorPage(...)` to wrap it.
3. Return the result via `Response.ok(html).build()` as before.

Affected methods (based on current code):

| Controller | Method | Page title |
|---|---|---|
| `DownloadAsyncController` | `showSubmitForm` | `"Submit Download"` |
| `DownloadAsyncController` | `submitDownload` (202, 400, 401, 500) | `"Download Submitted"` / error pages |
| `DownloadAsyncController` | `getDownloadStatus` (200, 401, 404) | `"Download Status — {uuid}"` |
| `DownloadAsyncController` | `getChunk` (401, 404) | error pages only (chunk response is `text/plain`) |
| `DownloadAsyncController` | `listDownloads` (200, 401) | `"Active Downloads"` |
| `DownloadController` | `base64Download` (500) | error page only (200 response is existing HTML) |
| `LoginController` | `login` (GET) | `"Login"` |
| `LoginController` | `login2` (POST) | `"Login"` |

The `DownloadController.base64Download()` 200-response page (which wraps raw Base64 text in a
`<div style="word-wrap:break-word">`) keeps its existing structure but should be wrapped in
`HtmlService.page()` for the outer chrome.

### 13.3 — Status page enhancements

When `HtmlService` is in place, improve the status page (`getDownloadStatus`) body:

- Show the status as a coloured badge from `HtmlService.statusBadge()` rather than plain text.
- Use `HtmlService.table()` for the request-details table (UUID, URL, file, submitted, expires,
  status).
- For `DONE`: list chunks in a `<table>` (not `<ol>`) with columns:
  `#` | `Filename` | `CRC32` | `MD5` | `Download link` — so checksums (from Task 9) are
  visible at a glance in a tabular layout.
- For `IN_PROGRESS` / `PENDING`: add a `<meta http-equiv="refresh" content="5">` tag in
  `extraHeadHtml` so the page auto-refreshes every 5 seconds without any JavaScript.

The auto-refresh `<meta>` tag is the only HTML-native way to poll for status without
JavaScript; it is appropriate here and does not violate the "minimal JS" principle.
