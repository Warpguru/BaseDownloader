package edu.java.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import edu.java.application.Constants;

/**
 * Application-scoped CDI bean that manages the on-disk storage of Base64 chunk files.
 *
 * <h2>Why a configurable base directory?</h2>
 * <p>
 * The Liberty working directory (and the JVM temp directory) may reside on a small or read-only partition in production
 * environments. Making the chunk storage path configurable via the MicroProfile Config property {@code bd.chunk.dir} (set in
 * {@code server.xml} as a Liberty {@code <variable>}) lets operators redirect chunk files to any suitable filesystem location
 * without rebuilding the application.
 * </p>
 *
 * <h2>Why the disk filename mirrors the user-visible name?</h2>
 * <p>
 * The on-disk filename ({@code {originalFileName}.{chunkIndex}.txt}) is intentionally identical to the
 * {@code Content-Disposition} filename returned by the chunk download endpoint. This 1:1 correspondence means that an operator
 * can locate and inspect any chunk file directly on disk using the same name they see on the status page, which aids debugging
 * and manual recovery when a download fails partway through.
 * </p>
 *
 * <h2>Why UTF-8 for writing?</h2>
 * <p>
 * Base64 output is pure ASCII, which is a strict subset of UTF-8. Any text editor, shell command ({@code cat}, {@code type}),
 * or integrity tool ({@code md5sum}, {@code certutil}) can open the file without charset conversion.
 * </p>
 */
@ApplicationScoped
public class ChunkStorageService {

    @Inject
    @ConfigProperty(name = Constants.CONFIG_BD_CHUNK_DIR)
    private String chunkBaseDir;

    /**
     * Returns the {@link Path} for the chunk file corresponding to the given parameters.
     * <p>
     * The path follows the pattern {@code {chunkBaseDir}/{uuid}/{originalFileName}.{chunkIndex}.txt}. The directory is not
     * created by this method; use {@link #writeChunk} to create and populate it.
     * </p>
     *
     * @param uuid             UUID of the owning download task
     * @param chunkIndex       1-based chunk index
     * @param originalFileName original filename from the URL path
     * @return resolved {@link Path} for the chunk file
     */
    public Path resolveChunkFile(final String uuid, final int chunkIndex, final String originalFileName) {
        return Paths.get(chunkBaseDir, uuid, originalFileName + "." + chunkIndex + Constants.CHUNK_FILE_EXTENSION);
    }

    /**
     * Writes {@code base64Content} as a UTF-8 text file at the path returned by {@link #resolveChunkFile}, creating the parent
     * directory if necessary.
     *
     * @param uuid             UUID of the owning download task
     * @param chunkIndex       1-based chunk index
     * @param originalFileName original filename from the URL path
     * @param base64Content    the Base64-encoded text to persist
     * @throws IOException if the file cannot be created or written
     */
    public void writeChunk(final String uuid, final int chunkIndex, final String originalFileName, final String base64Content)
            throws IOException {
        final Path chunkFile = resolveChunkFile(uuid, chunkIndex, originalFileName);
        Files.createDirectories(chunkFile.getParent());
        Files.write(chunkFile, base64Content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads and returns the UTF-8 text content of the chunk file identified by the given parameters.
     *
     * @param uuid             UUID of the owning download task
     * @param chunkIndex       1-based chunk index
     * @param originalFileName original filename from the URL path
     * @return the Base64-encoded text content of the chunk
     * @throws IOException if the chunk file does not exist or cannot be read
     */
    public String readChunk(final String uuid, final int chunkIndex, final String originalFileName) throws IOException {
        final Path chunkFile = resolveChunkFile(uuid, chunkIndex, originalFileName);
        return new String(Files.readAllBytes(chunkFile), StandardCharsets.UTF_8);
    }

    /**
     * Deletes the directory {@code {chunkBaseDir}/{uuid}/} and all its contents recursively.
     * <p>
     * Called by {@link DownloadTaskRegistry#removeExpired()} when a task is evicted from the registry. A failure to delete is
     * logged but does not prevent the registry entry from being removed.
     * </p>
     *
     * @param uuid UUID of the task whose chunk directory should be deleted
     */
    /**
     * Returns the directory path where all chunks for the given task UUID are stored. This is the directory the user must
     * {@code cd} into to run the reassembly commands.
     *
     * @param uuid UUID of the owning download task
     * @return absolute path of the task chunk directory
     */
    public Path getTaskDirectory(final String uuid) {
        final Path dir = Paths.get(chunkBaseDir, uuid);
        try {
            // toRealPath() resolves 8.3 short names (e.g. ROMANS~1 → RomanStangl) and symlinks.
            // The directory may not exist yet (task just registered), so fall back gracefully.
            return dir.toRealPath();
        } catch (IOException e) {
            return dir.toAbsolutePath();
        }
    }

    public void deleteTaskDirectory(final String uuid) {
        final Path taskDir = Paths.get(chunkBaseDir, uuid);
        deleteTree(taskDir);
    }

    /**
     * Deletes the entire {@code {chunkBaseDir}/} tree and recreates an empty directory.
     * <p>
     * Called at application startup (Task 11) to remove any chunk files left over from a previous server run that was not shut
     * down cleanly.
     * </p>
     *
     * @throws IOException if the directory cannot be deleted or recreated
     */
    public void deleteAllTaskDirectories() throws IOException {
        final Path baseDir = Paths.get(chunkBaseDir);
        if (Files.exists(baseDir)) {
            deleteTree(baseDir);
        }
        Files.createDirectories(baseDir);
    }

    /**
     * Recursively deletes {@code root} and all its contents. Errors are printed to stdout (matching the existing project
     * logging style) but not rethrown.
     *
     * @param root the directory (or file) to delete
     */
    private void deleteTree(final Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.out.println("ChunkStorageService: failed to delete " + root + ": " + e.getMessage());
        }
    }

}
