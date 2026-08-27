package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.ProcessState;
import org.junit.Test;

import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Covers the failure paths that must never take down the worker. */
public class ProcessResilienceTest {

    @Test
    public void repeatedStartDoesNotSpawnASecondEndlessRun() throws Exception {
        CountingEndlessProcess process = new CountingEndlessProcess();
        process.setType(com.synapsys.springboot.starter.model.ProcessType.ENDLESS);

        process.handleStart(1L, null);
        // Second command lands before the first thread has necessarily been scheduled.
        process.handleStart(2L, null);
        process.handleStart(3L, null);

        assertTrue(process.started.await(2, TimeUnit.SECONDS));
        Thread.sleep(150);
        assertEquals(1, process.runs.get());

        process.release.countDown();
    }

    @Test
    public void endlessFailureIsRecordedWithoutEscapingTheThread() throws Exception {
        FailingEndlessProcess process = new FailingEndlessProcess();
        process.handleStart(1L, null);

        assertTrue(awaitState(process, ProcessState.FAILED));
    }

    @Test
    public void progressiveFailureIsRecordedRatherThanSwallowedByTheFuture() throws Exception {
        FailingProgressiveProcess process = new FailingProgressiveProcess();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            process.handleStart(1L, exec);
            assertTrue(awaitState(process, ProcessState.FAILED));
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    public void aThrowingOnStopDoesNotPropagateToTheCaller() throws Exception {
        ThrowingStopEndlessProcess process = new ThrowingStopEndlessProcess();
        process.handleStart(1L, null);
        assertTrue(process.started.await(2, TimeUnit.SECONDS));

        // Must not throw: the heartbeat thread calls this.
        process.handleStop();

        assertTrue(awaitState(process, ProcessState.IDLE));
    }

    @Test
    public void uninstallRestoresTheOriginalConsoleStreams() {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        ProcessLogCapture capture = new ProcessLogCapture();
        capture.install();
        assertNotSame(originalOut, System.out);

        capture.uninstall();
        assertSame(originalOut, System.out);
        assertSame(originalErr, System.err);
    }

    @Test
    public void drainBudgetsMultiByteMessagesByUtf8Length() {
        ProcessLogCapture capture = new ProcessLogCapture();
        PrintStream originalOut = System.out;
        capture.install();
        try {
            try (ProcessLogCapture.Scope ignored = capture.open("demo", 1L)) {
                // Three bytes per character in UTF-8, one per char in String.length().
                System.out.println("日".repeat(40));
                System.out.println("日".repeat(40));
            }
            // 120 UTF-8 bytes per line: a 150-byte budget must fit exactly one.
            assertEquals(1, capture.drain(10, 150).size());
        } finally {
            capture.uninstall();
            System.setOut(originalOut);
        }
    }

    private static boolean awaitState(AbstractSynapsysProcess process, ProcessState expected)
            throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (process.getCurrentState() == expected) return true;
            Thread.sleep(10);
        }
        return false;
    }

    private static class CountingEndlessProcess extends AbstractEndlessProcess {
        private final AtomicInteger runs = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        protected void runForever(StopSignal stopSignal) throws Exception {
            runs.incrementAndGet();
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
        }
    }

    private static class FailingEndlessProcess extends AbstractEndlessProcess {
        @Override
        protected void runForever(StopSignal stopSignal) {
            throw new IllegalStateException("boom");
        }
    }

    private static class FailingProgressiveProcess extends AbstractProgressiveProcess {
        @Override
        protected void run() {
            throw new IllegalStateException("boom");
        }
    }

    private static class ThrowingStopEndlessProcess extends AbstractEndlessProcess {
        private final CountDownLatch started = new CountDownLatch(1);

        @Override
        protected void runForever(StopSignal stopSignal) {
            started.countDown();
            while (!stopSignal.isStopRequested()) {
                Thread.yield();
            }
        }

        @Override
        protected void onStop() {
            throw new IllegalStateException("cleanup exploded");
        }
    }
}
