package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.ProcessState;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

public abstract class AbstractProgressiveProcess extends AbstractSynapsysProcess {

    private final AtomicReference<Future<?>> runningFuture = new AtomicReference<>(null);
    private volatile boolean stopRequested = false;

    @Override
    public final void handleStart(Long runId, ExecutorService progressiveExecutor) {
        if (progressiveExecutor == null) {
            throw new IllegalStateException("Progressive executor is required for process " + getName());
        }

        if (getCurrentState() != ProcessState.IDLE && getCurrentState() != ProcessState.FAILED) return;

        Future<?> existing = runningFuture.get();
        if (existing != null && !existing.isDone()) return;

        stopRequested = false;
        Future<?> f = progressiveExecutor.submit(() -> {
            boolean cancelled = false;
            boolean failed = false;
            try (ProcessLogCapture.Scope ignored = openExecutionLogScope(runId)) {
                setCurrentState(ProcessState.RUNNING);
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
                    throw new RuntimeException("Failed to run process: " + getName(), e);
                }
            } finally {
                if (cancelled || stopRequested) {
                    try {
                        onStop();
                    } catch (Exception ignored) {
                    }
                }
                if (!failed) {
                    setCurrentState(ProcessState.IDLE);
                }
                stopRequested = false;
                runningFuture.set(null);
            }
        });

        runningFuture.set(f);
    }

    @Override
    public final void handleStop() {
        Future<?> f = runningFuture.get();
        if (f == null || f.isDone()) return;

        // heartbeat thread only updates state + triggers interrupt
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
