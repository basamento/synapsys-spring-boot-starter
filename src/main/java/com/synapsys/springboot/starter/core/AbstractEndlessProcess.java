package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.ProcessState;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractEndlessProcess extends AbstractSynapsysProcess {

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Thread worker;

    @Override
    public final void handleStart(Long runId, ExecutorService progressiveExecutor) {
        if (getCurrentState() == ProcessState.RUNNING || getCurrentState() == ProcessState.STOPPING) return;

        stopRequested.set(false);

        Thread t = new Thread(() -> {
            boolean failed = false;
            try (ProcessLogCapture.Scope ignored = openExecutionLogScope(runId)) {
                setCurrentState(ProcessState.RUNNING);
                try {
                    runForever(() -> stopRequested.get());
                } catch (Exception e) {
                    if (!stopRequested.get()) {
                        failed = true;
                        setCurrentState(ProcessState.FAILED);
                        captureExecutionException(e);
                        throw new RuntimeException("Endless process crashed: " + getName(), e);
                    }
                }
            } finally {
                if (!failed) {
                    setCurrentState(ProcessState.IDLE);
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
        if (getCurrentState() != ProcessState.RUNNING) return;

        try {
            setCurrentState(ProcessState.STOPPING);
            stopRequested.set(true);
            onStop();

            Thread t = worker;
            if (t != null) {
                t.interrupt(); // in case user is blocked on sleep/wait
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to stop endless process: " + getName(), e);
        }
    }

    @FunctionalInterface
    public interface StopSignal {
        boolean isStopRequested();
    }

    protected abstract void runForever(StopSignal stopSignal) throws Exception;

    protected void onStop() throws Exception {}
}
