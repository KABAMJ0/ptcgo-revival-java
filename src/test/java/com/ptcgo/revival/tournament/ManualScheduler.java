package com.ptcgo.revival.tournament;

import java.util.ArrayList;
import java.util.List;

/** Collects scheduled tasks; the test decides when they run. */
public final class ManualScheduler implements Scheduler {
    public record Task(long delayMs, Runnable run) {}

    private final List<Task> tasks = new ArrayList<>();

    @Override
    public synchronized void schedule(long delayMs, Runnable task) {
        tasks.add(new Task(delayMs, task));
    }

    public synchronized List<Long> delays() {
        return tasks.stream().map(Task::delayMs).toList();
    }

    public synchronized int size() {
        return tasks.size();
    }

    /** Runs (and removes) everything scheduled so far, once; tasks they schedule stay queued. */
    public void runAll() {
        List<Task> batch;
        synchronized (this) {
            batch = new ArrayList<>(tasks);
            tasks.clear();
        }
        batch.forEach(t -> t.run().run());
    }

    public synchronized void clear() {
        tasks.clear();
    }
}
