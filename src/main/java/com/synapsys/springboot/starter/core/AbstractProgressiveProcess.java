package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.ProcessState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public abstract class AbstractProgressiveProcess extends AbstractSynapsysProcess {

    private static final Logger log = LoggerFactory.getLogger(AbstractProgressiveProcess.class);

    private final AtomicReference<Future<?>> runningFuture = new AtomicReference<>(null);
    private volatile boolean stopRequested = false;

    /** Open while the body, and any cleanup it triggers, has not yet returned. */
    private volatile CountDownLatch inFlight;

    @Override
    public final synchronized Outcome handleStart(Long runId, ExecutorService progressiveExecutor) {
        if (progressiveExecutor == null) {
            throw new IllegalStateException(
                    SynapsysLog.PREFIX + "Progressive executor is required for process " + getName());
        }

        // A duplicate start for the execution already running is an idempotent no-op.
        if (getCurrentState() == ProcessState.RUNNING) return Outcome.HANDLED;

        // Stopping, or a body that ignored cancellation and is still in flight: the
        // replacement execution waits, unacknowledged, until this run is terminal.
        if (getCurrentState() == ProcessState.STOPPING || isInFlight()) return Outcome.DEFERRED;

        stopRequested = false;
        CountDownLatch latch = new CountDownLatch(1);
        inFlight = latch;
        setDesiredRunIdInternal(runId);

        // Set before submitting, so a second START cannot observe IDLE and queue a duplicate.
        setCurrentState(ProcessState.RUNNING);

        try {
            Future<?> f = progressiveExecutor.submit(() -> runBody(runId, latch));
            runningFuture.set(f);
        } catch (Throwable submitFailure) {
            inFlight = null;
            latch.countDown();
            setCurrentState(ProcessState.FAILED);
            log.error("{}Could not start progressive process '{}'",
                    SynapsysLog.PREFIX, getName(), submitFailure);
        }
        return Outcome.HANDLED;
    }

    private void runBody(Long runId, CountDownLatch latch) {
        boolean cancelled = false;
        boolean failed = false;
        try {
            try (ProcessLogCapture.Scope ignored = openExecutionLogScope(runId)) {
                try {
                    run();
                } catch (CancellationException ce) {
                    cancelled = true;
                } catch (InterruptedException ie) {
                    cancelled = true;
                    Thread.currentThread().interrupt();
                } catch (Throwable e) {
                    // Throwable, not Exception: an Error would otherwise land in a Future
                    // nothing inspects and be lost without a trace.
                    failed = true;
                    setCurrentState(ProcessState.FAILED);
                    captureExecutionException(e);
                    log.error("{}Progressive process '{}' failed", SynapsysLog.PREFIX, getName(), e);
                }

                // Cleanup runs inside the capture scope, so anything it prints reaches the
                // console in Core against this run.
                if (cancelled || stopRequested) {
                    try {
                        onStop();
                    } catch (Throwable e) {
                        log.error("{}onStop() failed for progressive process '{}'",
                                SynapsysLog.PREFIX, getName(), e);
                        captureExecutionException(e);
                    }
                }
            }
        } finally {
            if (!failed) {
                setCurrentState(ProcessState.IDLE);
            }
            stopRequested = false;
            runningFuture.set(null);
            latch.countDown();
        }
    }

    @Override
    public final synchronized Outcome handleStop() {
        Future<?> f = runningFuture.get();
        // A stop for a process with no run in flight is an idempotent handled no-op.
        if (f == null || f.isDone()) return Outcome.HANDLED;
        if (getCurrentState() == ProcessState.STOPPING) return Outcome.HANDLED;

        // The heartbeat thread only flags intent and interrupts; cleanup runs on the worker.
        stopRequested = true;
        setCurrentState(ProcessState.STOPPING);
        f.cancel(true);
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

    protected final void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Cancelled process: " + getName());
        }
    }

    protected abstract void run() throws Exception;

    // Cleanup hook runs in worker thread after cancel/interrupt
    protected void onStop() throws Exception {}
}
