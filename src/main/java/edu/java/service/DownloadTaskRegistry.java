package edu.java.service;

import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

import javax.enterprise.context.ApplicationScoped;

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
     * Removes all downloadTasks whose {@link DownloadTask#expiresAt} timestamp is strictly before {@link Instant#now()}.
     * <p>
     * This method is called periodically by {@code DownloadCleanupScheduler} (Task 7). Removing a task also releases the
     * {@link DownloadTask#chunks} list — and therefore all Base64-encoded strings held inside it — from heap memory, preventing
     * unbounded growth of the in-memory registry over time.
     * </p>
     */
    public void removeExpired() {
        final Instant now = Instant.now();
        downloadTasks.entrySet().removeIf(entry -> entry.getValue().getExpiresAt().isBefore(now));
    }

}
