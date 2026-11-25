package com.ptcgo.revival.tournament;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Delayed execution seam for round countdowns and the post-match nudges (tests drive it manually). */
public interface Scheduler {
    void schedule(long delayMs, Runnable task);

    /** Real timers on one daemon thread; tasks take the manager lock themselves. */
    static Scheduler system() {
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tournament-scheduler");
            t.setDaemon(true);
            return t;
        });
        return (delayMs, task) -> ses.schedule(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(Scheduler.class).error("Scheduled tournament task failed", e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }
}
