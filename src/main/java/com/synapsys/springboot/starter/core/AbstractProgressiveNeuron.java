package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.NeuronState;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

public abstract class AbstractProgressiveNeuron extends AbstractSynapsysNeuron {

    private final AtomicReference<Future<?>> runningFuture = new AtomicReference<>(null);

    @Override
    public final void handleStart(Long runId, ExecutorService progressiveExecutor) {
        if (progressiveExecutor == null) {
            throw new IllegalStateException("Progressive executor is required for neuron " + getName());
        }

        if (getCurrentState() != NeuronState.IDLE) return;

        Future<?> existing = runningFuture.get();
        if (existing != null && !existing.isDone()) return;

        Future<?> f = progressiveExecutor.submit(() -> {
            boolean cancelled = false;
            try {
                setCurrentState(NeuronState.RUNNING);
                run();
            } catch (CancellationException ce) {
                cancelled = true;
            } catch (InterruptedException ie) {
                cancelled = true;
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                throw new RuntimeException("Failed to run neuron: " + getName(), e);
            } finally {
                // If we were cancelled/stopped, call onStop here (worker thread), not heartbeat thread.
                if (cancelled) {
                    try {
                        onStop();
                    } catch (Exception ignored) {
                        // don't break state cleanup
                    }
                }
                setCurrentState(NeuronState.IDLE);
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
        setCurrentState(NeuronState.STOPPING);
        f.cancel(true);
    }

    protected final void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Cancelled neuron: " + getName());
        }
    }

    protected abstract void run() throws Exception;

    // Cleanup hook runs in worker thread after cancel/interrupt
    protected void onStop() throws Exception {}
}
