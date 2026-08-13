package edu.java.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.java.application.Constants;
import edu.java.rest.InfoController;

/**
 * EJB singleton that cleans up orphaned chunk directories from previous server runs on application startup.
 *
 * <h2>Why {@code @Startup}?</h2>
 * <p>
 * Without {@code @Startup}, Liberty may defer singleton initialisation until the first business method call, which would mean
 * the cleanup only happens when the first HTTP request arrives rather than immediately at server start. {@code @Startup} forces
 * the EJB container to instantiate and initialise this bean eagerly, so the cleanup is guaranteed to run before any request is
 * served.
 * </p>
 *
 * <h2>Why orphaned directories accumulate?</h2>
 * <p>
 * The in-memory {@link DownloadTaskRegistry} is lost on server restart, but the chunk files written to disk by
 * {@link ChunkStorageService} survive. After a restart the registry is empty, so the scheduled {@link DownloadCleanupScheduler}
 * has nothing to evict and the on-disk directories from the previous run are never deleted. Over time, especially in
 * crash-restart cycles, these orphaned directories accumulate and consume disk space indefinitely.
 * </p>
 *
 * <h2>Why wipe the entire base directory rather than matching against the registry?</h2>
 * <p>
 * At startup the registry is always empty (it is an in-memory {@link java.util.concurrent.ConcurrentHashMap} with no
 * persistence). Every directory that exists under {@code bd.chunk.dir} at startup time is therefore by definition an orphan
 * from a previous run. Attempting to match directories against the registry would always yield zero live tasks, so selectively
 * deleting only "unrecognised" UUIDs is equivalent to deleting all of them. The simpler
 * {@link ChunkStorageService#deleteAllTaskDirectories()} call is correct and unambiguous.
 * </p>
 */
@Singleton
@Startup
public class StartupCleanupService {

    private static Logger logger = LoggerFactory.getLogger(StartupCleanupService.class);

    @Inject
    private ChunkStorageService chunkStorageService;

    @Inject
    @ConfigProperty(name = Constants.CONFIG_BD_CHUNK_DIR)
    private String chunkBaseDir;

    /**
     * Deletes all chunk directories left over from a previous server run.
     * <p>
     * Invoked automatically by the EJB container immediately after this singleton is instantiated (before the first HTTP
     * request is dispatched). The method counts the top-level UUID subdirectories present before deletion so the number of
     * orphaned tasks cleaned up can be logged. The base directory is recreated empty after the wipe so that subsequent chunk
     * writes do not need to create it.
     * </p>
     */
    @PostConstruct
    public void cleanupOnStartup() {
        logger.info("StartupCleanupService running ...");
        final Path baseDir = Paths.get(chunkBaseDir);
        logger.info("  Cleaning directory: " + baseDir);
        int count = 0;
        if (Files.exists(baseDir)) {
            try {
                final List<Path> topLevel = Files.list(baseDir).collect(Collectors.toList());
                count = topLevel.size();
            } catch (IOException e) {
                System.out.println("StartupCleanupService: could not list " + baseDir + ": " + e.getMessage());
            }
        }
        try {
            chunkStorageService.deleteAllTaskDirectories();
            System.out.println("StartupCleanupService: cleaned up " + count + " orphaned task director"
                    + (count == 1 ? "y" : "ies") + " from previous run under " + baseDir);
        } catch (IOException e) {
            System.out.println("StartupCleanupService: failed to clean up " + baseDir + ": " + e.getMessage());
        } finally {
            logger.info("StartupCleanupService finished ...");
        }
    }

}
