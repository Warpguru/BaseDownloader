package edu.java.service;

import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application-scoped registry that tracks all active {@link DownloadTask} instances.
 * <p>
 * The backing store is a {@link ConcurrentHashMap}, which provides thread-safety for all read and write operations without
 * requiring explicit synchronisation. This bean is {@link ApplicationScoped} so exactly one instance is shared across the
 * entire application.
 * </p>
 */
@ApplicationScoped
public class DownloadTaskRegistry {

    private static final Logger logger = LoggerFactory.getLogger(DownloadTaskRegistry.class);

    /** Storage service used to delete on-disk chunk directories when a task expires. */
    @Inject
    private ChunkStorageService chunkStorageService;

    /** Map of {@link DownloadTask} by download request handle ({@code UUID}). */
    private final ConcurrentHashMap<String, DownloadTask> downloadTasks = new ConcurrentHashMap<>();

    /**
     * Registers a newly created task in the registry.
     * <p>
     * The task is stored under its {@link DownloadTask#uuid}; subsequent lookups must use the same UUID.
     * </p>
     *
     * @param task the task to register; must not be {@code null}
     */
    public void register(final DownloadTask task) {
        downloadTasks.put(task.getUuid(), task);
    }

    /**
     * Returns the task with the given UUID, or {@code null} if no such task exists.
     *
     * @param uuid unique identifier of the task
     * @return the {@link DownloadTask}, or {@code null}
     */
    public DownloadTask retrieve(final String uuid) {
        return downloadTasks.get(uuid);
    }

    /**
     * Returns an unmodifiable snapshot of all downloadTasks currently held in the registry.
     *
     * @return all registered downloadTasks
     */
    public Collection<DownloadTask> retrieveAll() {
        return downloadTasks.values();
    }

    /**
     * Removes the task with the given UUID from the registry, freeing any memory held by its chunk list.
     *
     * @param uuid unique identifier of the task to remove
     */
    public void remove(final String uuid) {
        downloadTasks.remove(uuid);
    }

    /**
     * Removes all tasks whose {@link DownloadTask#expiresAt} timestamp is strictly before {@link Instant#now()}.
     * <p>
     * This method is called periodically by {@code DownloadCleanupScheduler}. Both the in-memory registry entry and the on-disk
     * chunk directory are removed together: after removing the map entry, {@link ChunkStorageService#deleteTaskDirectory} is
     * called for each evicted task. A failure to delete the directory is logged but does not prevent the registry entry from
     * being removed.
     * </p>
     */
    public void removeExpired() {
        final Instant now = Instant.now();
        // Collect expired tasks first so we can delete their directories after map removal
        final java.util.List<DownloadTask> expiredDownloadTasks = new java.util.ArrayList<>();
        for (final DownloadTask task : downloadTasks.values()) {
            if (task.getExpiresAt().isBefore(now)) {
                expiredDownloadTasks.add(task);
            }
        }
        for (final DownloadTask downloadTask : expiredDownloadTasks) {
            downloadTasks.remove(downloadTask.getUuid());
            try {
                chunkStorageService.deleteTaskDirectory(downloadTask.getUuid());
            } catch (Exception e) {
                logger.error("Failed to delete chunk dir for uuid={}: {}", downloadTask.getUuid(), e.getMessage(), e);
            }
        }
    }

}
