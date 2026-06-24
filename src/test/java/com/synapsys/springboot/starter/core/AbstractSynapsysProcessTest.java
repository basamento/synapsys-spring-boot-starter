package com.synapsys.springboot.starter.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsys.springboot.starter.model.ProcessState;
import com.synapsys.springboot.starter.model.ProcessType;
import com.synapsys.springboot.starter.model.dto.request.HeartbeatProcessesRequestDTO;
import com.synapsys.springboot.starter.model.dto.request.ProcessInfoDTO;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AbstractSynapsysProcessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void acceptsOnlyNewerCommandTokens() {
        TestProgressiveProcess process = new TestProgressiveProcess();

        AbstractSynapsysProcess.CommandDecision start =
                process.acceptCoreCommand(ProcessState.RUNNING, 1L, 10L);
        AbstractSynapsysProcess.CommandDecision duplicate =
                process.acceptCoreCommand(ProcessState.STOPPING, 1L, null);
        assertEquals(1L, process.getAckToken().longValue());

        AbstractSynapsysProcess.CommandDecision stop =
                process.acceptCoreCommand(ProcessState.STOPPING, 2L, null);

        assertEquals(AbstractSynapsysProcess.Command.START, start.command());
        assertEquals(AbstractSynapsysProcess.Command.NONE, duplicate.command());
        assertEquals(AbstractSynapsysProcess.Command.STOP, stop.command());
        assertEquals(2L, process.getAckToken().longValue());
        assertEquals(ProcessState.STOPPING, process.getDesiredState());
    }

    @Test
    public void progressiveProcessCanRestartAfterFailure() throws Exception {
        FailingOnceProgressiveProcess process = new FailingOnceProgressiveProcess();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            process.handleStart(1L, executor);
            assertTrue(awaitState(process, ProcessState.FAILED));

            process.handleStart(2L, executor);
            assertTrue(process.completed.await(1, TimeUnit.SECONDS));
            assertTrue(awaitState(process, ProcessState.IDLE));
            assertEquals(2, process.runs.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void endlessProcessRemainsStoppingUntilWorkerExits() throws Exception {
        BlockingEndlessProcess process = new BlockingEndlessProcess();

        process.handleStart(null, null);
        assertTrue(process.started.await(1, TimeUnit.SECONDS));

        process.handleStop();
        assertTrue(process.stopSeen.await(1, TimeUnit.SECONDS));
        assertEquals(ProcessState.STOPPING, process.getCurrentState());
        process.release.set(true);
        assertTrue(process.exited.await(1, TimeUnit.SECONDS));
        assertTrue(awaitState(process, ProcessState.IDLE));
    }

    @Test
    public void heartbeatPayloadUsesWorkerAndProcessVocabulary() throws Exception {
        ProcessInfoDTO process = new ProcessInfoDTO(
                "daily-sync",
                ProcessType.PROGRESSIVE,
                ProcessState.IDLE,
                ProcessState.RUNNING
        );
        HeartbeatProcessesRequestDTO payload =
                new HeartbeatProcessesRequestDTO("billing-worker", List.of(process), 5000L);

        String json = MAPPER.writeValueAsString(payload);

        assertTrue(json.contains("\"workerName\""));
        assertTrue(json.contains("\"processes\""));
        assertTrue(json.contains("\"heartbeatInterval\""));
        assertFalse(json.contains("\"ne" + "urons\""));
        assertFalse(json.contains("\"ping" + "Timing\""));
    }

    private static boolean awaitState(AbstractSynapsysProcess process, ProcessState expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (process.getCurrentState() == expected) {
                return true;
            }
            Thread.sleep(10);
        }
        return process.getCurrentState() == expected;
    }

    private static class TestProgressiveProcess extends AbstractProgressiveProcess {
        @Override
        protected void run() {}
    }

    private static class FailingOnceProgressiveProcess extends AbstractProgressiveProcess {
        private final AtomicInteger runs = new AtomicInteger();
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        protected void run() {
            if (runs.incrementAndGet() == 1) {
                throw new IllegalStateException("first run fails");
            }
            completed.countDown();
        }
    }

    private static class BlockingEndlessProcess extends AbstractEndlessProcess {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch stopSeen = new CountDownLatch(1);
        private final CountDownLatch exited = new CountDownLatch(1);
        private final AtomicBoolean release = new AtomicBoolean(false);

        @Override
        protected void runForever(StopSignal stopSignal) {
            started.countDown();
            try {
                while (!stopSignal.isStopRequested()) {
                    Thread.yield();
                }
                stopSeen.countDown();
                while (!release.get()) {
                    Thread.yield();
                }
            } finally {
                exited.countDown();
            }
        }
    }
}
