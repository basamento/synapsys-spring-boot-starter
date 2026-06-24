package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.NeuronState;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractEndlessNeuron extends AbstractSynapsysNeuron {

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Thread worker;

    @Override
    public final void handleStart(Long runId, ExecutorService progressiveExecutor) {
        // Ignore runId, not meaningful for endless

        if (getCurrentState() == NeuronState.RUNNING || getCurrentState() == NeuronState.STOPPING) return;

        stopRequested.set(false);

        Thread t = new Thread(() -> {
            boolean failed = false;
            try {
                setCurrentState(NeuronState.RUNNING);
                runForever(() -> stopRequested.get());
            } catch (Exception e) {
                if (!stopRequested.get()) {
                    failed = true;
                    setCurrentState(NeuronState.FAILED);
                    throw new RuntimeException("Endless neuron crashed: " + getName(), e);
                }
            } finally {
                if (!failed) {
                    setCurrentState(NeuronState.IDLE);
                }
                worker = null;
            }
        }, "synapsys-endless-" + getName());

        t.setDaemon(true);
        worker = t;
        t.start();
    }

    @Override
    public final void handleStop() {
        if (getCurrentState() != NeuronState.RUNNING) return;

        try {
            setCurrentState(NeuronState.STOPPING);
            stopRequested.set(true);
            onStop();

            Thread t = worker;
            if (t != null) {
                t.interrupt(); // in case user is blocked on sleep/wait
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to stop endless neuron: " + getName(), e);
        }
    }

    @FunctionalInterface
    public interface StopSignal {
        boolean isStopRequested();
    }

    protected abstract void runForever(StopSignal stopSignal) throws Exception;

    protected void onStop() throws Exception {}
}
