package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.ProcessState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractEndlessProcess extends AbstractSynapsysProcess {

    private static final Logger log = LoggerFactory.getLogger(AbstractEndlessProcess.class);

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Thread worker;

    /**
     * Open while a body, or the cleanup hook belonging to it, has not yet returned.
     * A fresh latch is created for every accepted run and counted down exactly once.
     */
    private volatile CountDownLatch inFlight;

    /** Open while an accepted stop's onStop hook is still running, null when there is none. */
    private volatile CountDownLatch cleanupDone;

    @Override
    public final synchronized Outcome handleStart(Long runId, ExecutorService progressiveExecutor) {
        // A duplicate start for the execution already running is an idempotent no-op,
        // and must not silently reassign the active run.
        if (getCurrentState() == ProcessState.RUNNING) return Outcome.HANDLED;

        // Stopping, or business logic that ignored cancellation and is still alive: the
        // replacement execution waits rather than overlapping it. The token stays
        // unacknowledged, so Core redelivers it once this run is terminal.
        if (getCurrentState() == ProcessState.STOPPING || isInFlight()) return Outcome.DEFERRED;

        stopRequested.set(false);
        cleanupDone = null;
        CountDownLatch latch = new CountDownLatch(1);
        inFlight = latch;
        setDesiredRunIdInternal(runId);

        // RUNNING is set here, not inside the thread: a second START arriving before the
        // thread is scheduled would otherwise still observe IDLE and start a duplicate.
        setCurrentState(ProcessState.RUNNING);

        Thread t = new Thread(() -> runBody(runId, latch), "synapsys-endless-" + getName());
        t.setDaemon(true);
        worker = t;
        try {
            t.start();
        } catch (Throwable startFailure) {
            worker = null;
            inFlight = null;
            latch.countDown();
            setCurrentState(ProcessState.FAILED);
            log.error("{}Could not start endless process '{}'", SynapsysLog.PREFIX, getName(), startFailure);
        }
        return Outcome.HANDLED;
    }

    private void runBody(Long runId, CountDownLatch latch) {
        Thread self = Thread.currentThread();
        boolean failed = false;
        try {
            try (ProcessLogCapture.Scope ignored = openExecutionLogScope(runId)) {
                try {
                    runForever(stopRequested::get);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable e) {
                    // Throwable, not Exception: an Error thrown by user code would
                    // otherwise escape this thread and print a bare stack trace on the
                    // host application's console, from a thread the user did not create.
                    if (!stopRequested.get()) {
                        failed = true;
                        setCurrentState(ProcessState.FAILED);
                        captureExecutionException(e);
                        log.error("{}Endless process '{}' failed", SynapsysLog.PREFIX, getName(), e);
                    }
                }
            }

            // The cleanup hook an accepted stop started must settle before this process
            // can accept a replacement execution.
            CountDownLatch cleanup = cleanupDone;
            if (cleanup != null) {
                try {
                    cleanup.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            if (!failed) {
                setCurrentState(ProcessState.IDLE);
            }
            // Only clear the reference if a newer run has not already replaced it.
            if (worker == self) {
                worker = null;
            }
            latch.countDown();
        }
    }

    @Override
    public final synchronized Outcome handleStop() {
        // A stop for a process that is not running is an idempotent handled no-op.
        if (getCurrentState() != ProcessState.RUNNING) return Outcome.HANDLED;

        setCurrentState(ProcessState.STOPPING);
        stopRequested.set(true);

        // Claimed here, while this method's monitor is held, so a body returning at this
        // very moment cannot see "no cleanup outstanding" and let a replacement run in.
        CountDownLatch cleanup = new CountDownLatch(1);
        cleanupDone = cleanup;

        Thread t = worker;
        if (t != null) {
            t.interrupt(); // in case user code is blocked on sleep/wait
        }

        // onStop runs off the caller's thread. handleStop is called from the heartbeat
        // thread, and a slow cleanup hook must not delay the commands the same beat
        // carries for the other processes.
        Thread cleanupThread = new Thread(() -> {
            try {
                onStop();
            } catch (Throwable e) {
                // A failing cleanup hook must not abort the stop sequence.
                log.error("{}onStop() failed for endless process '{}'", SynapsysLog.PREFIX, getName(), e);
                captureExecutionException(e);
            } finally {
                cleanup.countDown();
            }
        }, "synapsys-cleanup-" + getName());
        cleanupThread.setDaemon(true);
        try {
            cleanupThread.start();
        } catch (Throwable startFailure) {
            cleanup.countDown();
            log.error("{}Could not run onStop() for endless process '{}'",
                    SynapsysLog.PREFIX, getName(), startFailure);
        }
        return Outcome.HANDLED;
    }

    @Override
    public final boolean isInFlight() {
        CountDownLatch latch = inFlight;
        return latch != null && latch.getCount() > 0;
    }

    @Override
    public final boolean settle(long timeoutMillis) {
        CountDownLatch latch = inFlight;
        if (latch == null) return true;
        try {
            return latch.await(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return latch.getCount() == 0;
        }
    }

    @FunctionalInterface
    public interface StopSignal {
        boolean isStopRequested();
    }

    protected abstract void runForever(StopSignal stopSignal) throws Exception;

    protected void onStop() throws Exception {}
}
