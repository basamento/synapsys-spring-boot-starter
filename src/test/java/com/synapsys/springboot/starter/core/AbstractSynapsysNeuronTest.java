package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.NeuronState;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AbstractSynapsysNeuronTest {

    @Test
    public void acceptsOnlyNewerCommandTokens() {
        TestProgressiveNeuron neuron = new TestProgressiveNeuron();

        AbstractSynapsysNeuron.CommandDecision start =
                neuron.acceptCoreCommand(NeuronState.RUNNING, 1L, 10L);
        AbstractSynapsysNeuron.CommandDecision duplicate =
                neuron.acceptCoreCommand(NeuronState.STOPPING, 1L, null);
        assertEquals(1L, neuron.getAckToken().longValue());

        AbstractSynapsysNeuron.CommandDecision stop =
                neuron.acceptCoreCommand(NeuronState.STOPPING, 2L, null);

        assertEquals(AbstractSynapsysNeuron.Command.START, start.command());
        assertEquals(AbstractSynapsysNeuron.Command.NONE, duplicate.command());
        assertEquals(AbstractSynapsysNeuron.Command.STOP, stop.command());
        assertEquals(2L, neuron.getAckToken().longValue());
        assertEquals(NeuronState.STOPPING, neuron.getDesiredState());
    }

    @Test
    public void progressiveNeuronCanRestartAfterFailure() throws Exception {
        FailingOnceProgressiveNeuron neuron = new FailingOnceProgressiveNeuron();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            neuron.handleStart(1L, executor);
            assertTrue(awaitState(neuron, NeuronState.FAILED));

            neuron.handleStart(2L, executor);
            assertTrue(neuron.completed.await(1, TimeUnit.SECONDS));
            assertTrue(awaitState(neuron, NeuronState.IDLE));
            assertEquals(2, neuron.runs.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void endlessNeuronRemainsStoppingUntilWorkerExits() throws Exception {
        BlockingEndlessNeuron neuron = new BlockingEndlessNeuron();

        neuron.handleStart(null, null);
        assertTrue(neuron.started.await(1, TimeUnit.SECONDS));

        neuron.handleStop();
        assertTrue(neuron.stopSeen.await(1, TimeUnit.SECONDS));
        assertEquals(NeuronState.STOPPING, neuron.getCurrentState());
        neuron.release.set(true);
        assertTrue(neuron.exited.await(1, TimeUnit.SECONDS));
        assertTrue(awaitState(neuron, NeuronState.IDLE));
    }

    private static boolean awaitState(AbstractSynapsysNeuron neuron, NeuronState expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (neuron.getCurrentState() == expected) {
                return true;
            }
            Thread.sleep(10);
        }
        return neuron.getCurrentState() == expected;
    }

    private static class TestProgressiveNeuron extends AbstractProgressiveNeuron {
        @Override
        protected void run() {}
    }

    private static class FailingOnceProgressiveNeuron extends AbstractProgressiveNeuron {
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

    private static class BlockingEndlessNeuron extends AbstractEndlessNeuron {
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
