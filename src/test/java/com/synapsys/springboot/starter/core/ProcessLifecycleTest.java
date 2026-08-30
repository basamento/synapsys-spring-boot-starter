package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.ProcessState;
import com.synapsys.springboot.starter.model.ProcessType;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The lifecycle rules shared with every other Synapsys client library: deferred starts,
 * idempotent no-ops, and cancellation that is not a failure.
 */
public class ProcessLifecycleTest {

    @Test
    public void aStartWhileStoppingIsDeferredAndKeepsTheActiveRunId() throws Exception {
        StubbornEndlessProcess process = new StubbornEndlessProcess();
        process.setType(ProcessType.ENDLESS);

        assertEquals(AbstractSynapsysProcess.Outcome.HANDLED, process.handleStart(100L, null));
        assertTrue(process.started.await(2, TimeUnit.SECONDS));

        assertEquals(AbstractSynapsysProcess.Outcome.HANDLED, process.handleStop());
        assertEquals(ProcessState.STOPPING, process.getCurrentState());

        // The body deliberately ignores the stop signal, which is the case the deferral
        // rule exists for. The redelivered start must not run over the top of it.
        assertEquals(AbstractSynapsysProcess.Outcome.DEFERRED, process.handleStart(200L, null));
        assertEquals(1, process.runs.get());
        assertEquals(Long.valueOf(100L), process.getDesiredRunId());

        process.release.countDown();
        assertTrue(process.settle(5_000));
        assertEquals(ProcessState.IDLE, process.getCurrentState());

        // Once the old run is terminal, the redelivered command is accepted.
        assertEquals(AbstractSynapsysProcess.Outcome.HANDLED, process.handleStart(200L, null));
        assertTrue(awaitRuns(process, 2));
        assertEquals(Long.valueOf(200L), process.getDesiredRunId());

        process.release2.countDown();
        process.handleStop();
        process.settle(5_000);
    }

    @Test
    public void aDuplicateStartDoesNotReassignTheActiveRun() throws Exception {
        StubbornEndlessProcess process = new StubbornEndlessProcess();
        process.setType(ProcessType.ENDLESS);

        assertEquals(AbstractSynapsysProcess.Outcome.HANDLED, process.handleStart(7L, null));
        assertTrue(process.started.await(2, TimeUnit.SECONDS));

        assertEquals(AbstractSynapsysProcess.Outcome.HANDLED, process.handleStart(8L, null));
        assertEquals(1, process.runs.get());
        assertEquals(Long.valueOf(7L), process.getDesiredRunId());

        process.release.countDown();
        process.handleStop();
        process.settle(5_000);
    }

    @Test
    public void aStopForAnInactiveProcessIsAnIdempotentHandledNoOp() {
        StubbornEndlessProcess process = new StubbornEndlessProcess();
        process.setType(ProcessType.ENDLESS);

        assertEquals(AbstractSynapsysProcess.Outcome.HANDLED, process.handleStop());
        assertEquals(ProcessState.IDLE, process.getCurrentState());
        assertEquals(AbstractSynapsysProcess.Outcome.HANDLED, process.handleStop());
    }

    @Test
    public void aProgressiveStartWhileTheRunIsInFlightIsDeferred() throws Exception {
        BlockingProgressiveProcess process = new BlockingProgressiveProcess();
        process.setType(ProcessType.PROGRESSIVE);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertEquals(AbstractSynapsysProcess.Outcome.HANDLED, process.handleStart(1L, executor));
            assertTrue(process.started.await(2, TimeUnit.SECONDS));

            process.handleStop();
            assertEquals(AbstractSynapsysProcess.Outcome.DEFERRED, process.handleStart(2L, executor));
            assertEquals(1, process.runs.get());

            process.release.countDown();
            assertTrue(process.settle(5_000));

            assertEquals(AbstractSynapsysProcess.Outcome.HANDLED, process.handleStart(2L, executor));
            assertTrue(process.settle(5_000));
            assertEquals(2, process.runs.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void aCommandWithNoTokenIsIgnoredEntirely() {
        BlockingProgressiveProcess process = new BlockingProgressiveProcess();
        AbstractSynapsysProcess.CommandDecision decision =
                process.acceptCoreCommand(ProcessState.RUNNING, null, 5L);

        assertEquals(AbstractSynapsysProcess.Command.NONE, decision.command());
        assertNull(process.getDesiredRunId());
    }

    @Test
    public void acceptingAStartDoesNotAdoptItsRunIdOnItsOwn() {
        BlockingProgressiveProcess process = new BlockingProgressiveProcess();

        AbstractSynapsysProcess.CommandDecision decision =
                process.acceptCoreCommand(ProcessState.RUNNING, 1L, 99L);

        assertEquals(AbstractSynapsysProcess.Command.START, decision.command());
        assertEquals(Long.valueOf(99L), decision.runId());
        // The handler adopts it. Deciding the command must not touch the active run.
        assertNull(process.getDesiredRunId());
    }

    @Test
    public void anUnrecognisedDesiredStateIsAcknowledgedAndActedOnInNoWay() {
        BlockingProgressiveProcess process = new BlockingProgressiveProcess();

        // What a future Core sending a state this version has never heard of looks like
        // once ProcessState.fromValue has leniently mapped it to null.
        AbstractSynapsysProcess.CommandDecision decision =
                process.acceptCoreCommand(null, 3L, 12L);

        assertEquals(AbstractSynapsysProcess.Command.NONE, decision.command());
        assertEquals(3L, decision.token());
    }

    @Test
    public void aCooperativeStopIsNotAProcessFailure() throws Exception {
        CooperativeEndlessProcess process = new CooperativeEndlessProcess();
        process.setType(ProcessType.ENDLESS);

        process.handleStart(1L, null);
        assertTrue(process.started.await(2, TimeUnit.SECONDS));
        process.handleStop();
        assertTrue(process.settle(5_000));

        assertEquals(ProcessState.IDLE, process.getCurrentState());
    }

    @Test
    public void aThrowingCleanupHookDoesNotBlockTheHeartbeatThread() throws Exception {
        SlowCleanupEndlessProcess process = new SlowCleanupEndlessProcess();
        process.setType(ProcessType.ENDLESS);

        process.handleStart(1L, null);
        assertTrue(process.started.await(2, TimeUnit.SECONDS));

        long before = System.nanoTime();
        process.handleStop();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

        // onStop sleeps for 700ms. handleStop is called from the heartbeat thread and
        // must not wait for it, or the other processes on that beat wait too.
        assertTrue("handleStop blocked for " + elapsedMillis + "ms", elapsedMillis < 400);
        assertTrue(process.settle(5_000));
        assertEquals(ProcessState.IDLE, process.getCurrentState());
    }

    @Test
    public void anErrorThrownByUserCodeIsRecordedRatherThanEscapingTheThread() throws Exception {
        ErrorThrowingEndlessProcess process = new ErrorThrowingEndlessProcess();
        process.setType(ProcessType.ENDLESS);

        process.handleStart(1L, null);
        assertTrue(process.settle(5_000));
        assertEquals(ProcessState.FAILED, process.getCurrentState());
    }

    private static boolean awaitRuns(StubbornEndlessProcess process, int expected)
            throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (process.runs.get() >= expected) return true;
            Thread.sleep(10);
        }
        return false;
    }

    /**
     * Neither polls the stop signal nor honours an interrupt: exactly the legacy body
     * the deferral rule exists for, and the one the starter must never kill.
     */
    private static void waitIgnoringInterrupts(CountDownLatch gate) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (gate.getCount() > 0 && System.nanoTime() < deadline) {
            try {
                gate.await(20, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.interrupted(); // swallowed, and the flag cleared, on purpose
            }
        }
    }

    private static class StubbornEndlessProcess extends AbstractEndlessProcess {
        private final AtomicInteger runs = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch release2 = new CountDownLatch(1);

        @Override
        protected void runForever(StopSignal stopSignal) {
            int run = runs.incrementAndGet();
            started.countDown();
            waitIgnoringInterrupts(run == 1 ? release : release2);
        }
    }

    private static class BlockingProgressiveProcess extends AbstractProgressiveProcess {
        private final AtomicInteger runs = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        protected void run() {
            runs.incrementAndGet();
            started.countDown();
            waitIgnoringInterrupts(release);
        }
    }

    private static class CooperativeEndlessProcess extends AbstractEndlessProcess {
        private final CountDownLatch started = new CountDownLatch(1);

        @Override
        protected void runForever(StopSignal stopSignal) {
            started.countDown();
            while (!stopSignal.isStopRequested()) {
                Thread.yield();
            }
        }
    }

    private static class SlowCleanupEndlessProcess extends AbstractEndlessProcess {
        private final CountDownLatch started = new CountDownLatch(1);

        @Override
        protected void runForever(StopSignal stopSignal) {
            started.countDown();
            while (!stopSignal.isStopRequested()) {
                Thread.yield();
            }
        }

        @Override
        protected void onStop() throws Exception {
            Thread.sleep(700);
            throw new IllegalStateException("cleanup exploded");
        }
    }

    private static class ErrorThrowingEndlessProcess extends AbstractEndlessProcess {
        @Override
        protected void runForever(StopSignal stopSignal) {
            throw new StackOverflowError("user code blew the stack");
        }
    }
}
