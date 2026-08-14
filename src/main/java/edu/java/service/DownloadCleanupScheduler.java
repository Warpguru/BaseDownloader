package edu.java.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.ejb.Schedule;
import javax.ejb.Singleton;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EJB singleton scheduler that evicts expired {@link DownloadTask} instances from the {@link DownloadTaskRegistry} once per
 * minute.
 *
 * <h2>Schedule trade-off</h2>
 * <p>
 * The timer fires every minute ({@code minute = "*&#47;1"}). A shorter interval would free memory sooner but add unnecessary
 * overhead; a longer interval risks keeping large Base64 chunk strings in memory well past their expiry. One minute is a
 * practical compromise for this use case.
 * </p>
 *
 * <h2>{@code persistent = false}</h2>
 * <p>
 * Setting {@code persistent = false} prevents the Liberty EJB timer service from persisting the timer state to a store across
 * server restarts. On restart the timer is simply recreated from the {@code @Schedule} annotation. As a consequence, any
 * {@link DownloadTask} instances held in the in-memory {@link DownloadTaskRegistry} at shutdown are silently lost -- this is
 * acceptable because the registry itself is also in-memory and does not survive a restart.
 * </p>
 *
 * <p>
 * {@code @Singleton} is required here: the EJB timer service only supports {@code @Schedule} on singleton beans. The bean holds
 * no mutable state beyond the injected registry reference, so the default write-lock behaviour of {@code @Singleton} is not a
 * concern for the one timer callback that fires once per minute.
 * </p>
 */
@Singleton
public class DownloadCleanupScheduler {

    private static Logger logger = LoggerFactory.getLogger(DownloadCleanupScheduler.class);

    @Inject
    private DownloadTaskRegistry registry;

    /**
     * Removes all expired tasks from the registry and logs each removal.
     * <p>
     * Fired automatically by the EJB timer service at second 0 of every minute. The method snapshots the tasks that are about
     * to expire before delegating removal to {@link DownloadTaskRegistry#removeExpired()}, so that it can log each evicted
     * task's UUID, URL, and expiry timestamp.
     * </p>
     */
    @Schedule(hour = "*", minute = "*/1", second = "0", persistent = false)
    public void cleanupExpiredTasks() {
        logger.info("Cleanup scheduled ...");
        final Instant now = Instant.now();
        // Snapshot tasks that will be removed so we can log them after removal
        final List<DownloadTask> expired = new ArrayList<>();
        for (final DownloadTask task : registry.retrieveAll()) {
            if (task.getExpiresAt().isBefore(now)) {
                expired.add(task);
            }
        }
        if (expired.isEmpty()) {
            return;
        }
        registry.removeExpired();
        for (final DownloadTask task : expired) {
            logger.info("Evicted task uuid={} url={} expiresAt={}",
                    task.getUuid(), task.getRequestedUrl(), task.getExpiresAt());
        }
    }

}
