package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.ProcessState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

public abstract class AbstractProgressiveProcess extends AbstractSynapsysProcess {

    private static final Logger log = LoggerFactory.getLogger(AbstractProgressiveProcess.class);

    private final AtomicReference<Future<?>> runningFuture = new AtomicReference<>(null);
    private volatile boolean stopRequested = false;

    @Override
    public final synchronized void handleStart(Long runId, ExecutorService progressiveExecutor) {
        if (progressiveExecutor == null) {
            throw new IllegalStateException(
                    SynapsysLog.PREFIX + "Progressive executor is required for process " + getName());
        }

        if (getCurrentState() != ProcessState.IDLE && getCurrentState() != ProcessState.FAILED) return;

        Future<?> existing = runningFuture.get();
        if (existing != null && !existing.isDone()) return;

        stopRequested = false;

        // Set before submitting, so a second START cannot observe IDLE and queue a duplicate.
        setCurrentState(ProcessState.RUNNING);

        Future<?> f = progressiveExecutor.submit(() -> runBody(runId));
        runningFuture.set(f);
    }

    private void runBody(Long runId) {
        boolean cancelled = false;
        boolean failed = false;
        try (ProcessLogCapture.Scope ignored = openExecutionLogScope(runId)) {
            try {
                run();
            } catch (CancellationException ce) {
                cancelled = true;
            } catch (InterruptedException ie) {
                cancelled = true;
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                failed = true;
                setCurrentState(ProcessState.FAILED);
                captureExecutionException(e);
                // Reported, never rethrown: an exception thrown here lands in a Future
                // that nothing inspects, and would be lost silently.
                log.error("{}Progressive process '{}' failed", SynapsysLog.PREFIX, getName(), e);
            }
        } finally {
            if (cancelled || stopRequested) {
                try {
                    onStop();
                } catch (Exception e) {
                    log.error("{}onStop() failed for progressive process '{}'", SynapsysLog.PREFIX, getName(), e);
                    captureExecutionException(e);
                }
            }
            if (!failed) {
                setCurrentState(ProcessState.IDLE);
            }
            stopRequested = false;
            runningFuture.set(null);
        }
    }

    @Override
    public final synchronized void handleStop() {
        Future<?> f = runningFuture.get();
        if (f == null || f.isDone()) return;

        // The heartbeat thread only flags intent and interrupts; cleanup runs on the worker.
        stopRequested = true;
        setCurrentState(ProcessState.STOPPING);
        f.cancel(true);
    }

    protected final void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Cancelled process: " + getName());
        }
    }

    protected abstract void run() throws Exception;

    // Cleanup hook runs in worker thread after cancel/interrupt
    protected void onStop() throws Exception {}
}
