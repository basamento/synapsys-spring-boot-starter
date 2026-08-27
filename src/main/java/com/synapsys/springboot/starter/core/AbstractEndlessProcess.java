package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.ProcessState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractEndlessProcess extends AbstractSynapsysProcess {

    private static final Logger log = LoggerFactory.getLogger(AbstractEndlessProcess.class);

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Thread worker;

    @Override
    public final synchronized void handleStart(Long runId, ExecutorService progressiveExecutor) {
        if (getCurrentState() == ProcessState.RUNNING || getCurrentState() == ProcessState.STOPPING) return;

        stopRequested.set(false);

        // RUNNING is set here, not inside the thread: a second START arriving before the
        // thread is scheduled would otherwise still observe IDLE and start a duplicate.
        setCurrentState(ProcessState.RUNNING);

        Thread t = new Thread(() -> runBody(runId), "synapsys-endless-" + getName());
        t.setDaemon(true);
        worker = t;
        try {
            t.start();
        } catch (Throwable startFailure) {
            worker = null;
            setCurrentState(ProcessState.FAILED);
            log.error("{}Could not start endless process '{}'", SynapsysLog.PREFIX, getName(), startFailure);
        }
    }

    private void runBody(Long runId) {
        Thread self = Thread.currentThread();
        boolean failed = false;
        try (ProcessLogCapture.Scope ignored = openExecutionLogScope(runId)) {
            try {
                runForever(stopRequested::get);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (!stopRequested.get()) {
                    failed = true;
                    setCurrentState(ProcessState.FAILED);
                    captureExecutionException(e);
                    // Reported, never rethrown: an uncaught exception here would print a
                    // bare stack trace on the host application's console.
                    log.error("{}Endless process '{}' failed", SynapsysLog.PREFIX, getName(), e);
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
        }
    }

    @Override
    public final synchronized void handleStop() {
        if (getCurrentState() != ProcessState.RUNNING) return;

        setCurrentState(ProcessState.STOPPING);
        stopRequested.set(true);

        try {
            onStop();
        } catch (Exception e) {
            // A failing cleanup hook must not abort the stop sequence.
            log.error("{}onStop() failed for endless process '{}'", SynapsysLog.PREFIX, getName(), e);
            captureExecutionException(e);
        }

        Thread t = worker;
        if (t != null) {
            t.interrupt(); // in case user code is blocked on sleep/wait
        }
    }

    @FunctionalInterface
    public interface StopSignal {
        boolean isStopRequested();
    }

    protected abstract void runForever(StopSignal stopSignal) throws Exception;

    protected void onStop() throws Exception {}
}
