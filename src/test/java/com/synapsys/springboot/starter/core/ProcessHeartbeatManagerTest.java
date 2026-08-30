package com.synapsys.springboot.starter.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.synapsys.springboot.starter.client.Client;
import com.synapsys.springboot.starter.model.ProcessState;
import com.synapsys.springboot.starter.model.ProcessType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The heartbeat manager against a real HTTP stand-in for Core: command acknowledgement,
 * deferral, malformed responses, and the bounded shutdown that ends with one final beat.
 */
public class ProcessHeartbeatManagerTest {

    private HttpServer server;
    private String coreUrl;

    private final List<String> heartbeatBodies = Collections.synchronizedList(new ArrayList<>());
    private final List<String> heartbeatAuthorization = Collections.synchronizedList(new ArrayList<>());
    private final List<String> healthAuthorization = Collections.synchronizedList(new ArrayList<>());

    /** What Core answers with. Replaced per test. */
    private volatile String responseBody = "{\"processes\":[]}";
    private volatile int responseStatus = 200;
    private volatile long responseDelayMillis = 0;

    @Before
    public void startFakeCore() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/health", exchange -> {
            healthAuthorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/api/v1/workers/heartbeat", exchange -> {
            heartbeatAuthorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            heartbeatBodies.add(readBody(exchange));
            long delay = responseDelayMillis;
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        coreUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stopFakeCore() {
        if (server != null) server.stop(0);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ProcessHeartbeatManager manager(List<AbstractSynapsysProcess> processes) {
        return manager(processes, Duration.ofSeconds(10));
    }

    private ProcessHeartbeatManager manager(
            List<AbstractSynapsysProcess> processes, Duration shutdownTimeout) {
        return new ProcessHeartbeatManager(
                processes,
                Duration.ofSeconds(1),
                shutdownTimeout,
                coreUrl,
                "test-worker",
                "test-host",
                new Client(Duration.ofSeconds(2), Duration.ofSeconds(5), "test-token"),
                // Console capture replaces System.out process-wide; the test JVM keeps its own.
                false
        );
    }

    // ------------------------------------------------------------------ payload

    @Test
    public void theHeartbeatCarriesOnlyWhatTheProtocolAsksAWorkerToReport() throws Exception {
        CooperativeProcess process = named(new CooperativeProcess(), "poller", ProcessType.ENDLESS);
        ProcessHeartbeatManager manager = manager(List.of(process));
        try {
            manager.startHeartbeat();
            assertTrue(awaitBeats(1));

            String body = heartbeatBodies.get(0);
            assertTrue(body, body.contains("\"workerName\":\"test-worker\""));
            assertTrue(body, body.contains("\"framework\":\"springboot\""));
            assertTrue(body, body.contains("\"heartbeatInterval\":1"));
            assertTrue(body, body.contains("\"currentState\":\"idle\""));
            assertTrue(body, body.contains("\"ackToken\":0"));
            // desiredState and desiredToken belong to Core, not in a worker's report.
            assertFalse(body, body.contains("\"desiredState\""));
            assertFalse(body, body.contains("\"desiredToken\""));
            // The token travels in the header and nowhere else.
            assertFalse(body, body.contains("test-token"));
            assertEquals("Bearer test-token", heartbeatAuthorization.get(0));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void theHealthProbeDoesNotCarryTheBearerToken() {
        CoreValidatorHarness.probe(coreUrl);
        assertEquals(1, healthAuthorization.size());
        assertNull("Core serves /api/health unauthenticated", healthAuthorization.get(0));
    }

    // ------------------------------------------------------------------ commands

    @Test
    public void aStartIsAppliedAndAcknowledged() throws Exception {
        CooperativeProcess process = named(new CooperativeProcess(), "poller", ProcessType.ENDLESS);
        responseBody = command("poller", "running", 1, 12345);

        ProcessHeartbeatManager manager = manager(List.of(process));
        try {
            manager.startHeartbeat();
            assertTrue(awaitState(process, ProcessState.RUNNING));
            assertEquals(Long.valueOf(1L), process.getAckToken());
            assertEquals(Long.valueOf(12345L), process.getDesiredRunId());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void aStartWhileStoppingStaysUnacknowledgedUntilTheOldRunIsTerminal() throws Exception {
        StubbornProcess process = named(new StubbornProcess(), "stubborn", ProcessType.ENDLESS);
        responseBody = command("stubborn", "running", 1, 111);

        ProcessHeartbeatManager manager = manager(List.of(process));
        try {
            manager.startHeartbeat();
            assertTrue(awaitState(process, ProcessState.RUNNING));

            responseBody = command("stubborn", "stopping", 2, 111);
            assertTrue(awaitAck(process, 2));
            assertEquals(ProcessState.STOPPING, process.getCurrentState());

            // Core now wants a new run while the old one refuses to die.
            responseBody = command("stubborn", "running", 3, 222);
            Thread.sleep(1_500);
            assertEquals("the start must not be acknowledged",
                    Long.valueOf(2L), process.getAckToken());
            assertEquals("the old run id must not be replaced",
                    Long.valueOf(111L), process.getDesiredRunId());

            // The old run finally exits; the redelivered command is applied.
            process.release.countDown();
            assertTrue(awaitAck(process, 3));
            assertEquals(Long.valueOf(222L), process.getDesiredRunId());
            assertEquals(2, process.runs.get());
            process.release2.countDown();
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void aCommandForAnUnknownProcessIsIgnoredWithoutKillingTheLoop() throws Exception {
        CooperativeProcess process = named(new CooperativeProcess(), "poller", ProcessType.ENDLESS);
        responseBody = command("renamed-away", "running", 1, 5);

        ProcessHeartbeatManager manager = manager(List.of(process));
        try {
            manager.startHeartbeat();
            assertTrue(awaitBeats(2));
            assertEquals(ProcessState.IDLE, process.getCurrentState());
        } finally {
            manager.shutdown();
        }
    }

    // ------------------------------------------------------------------ transport

    @Test
    public void aMalformedTwoHundredBodyIsAProtocolFailureThatAcknowledgesNothing() throws Exception {
        CooperativeProcess process = named(new CooperativeProcess(), "poller", ProcessType.ENDLESS);
        responseBody = "{\"processes\": [{\"name\":";

        ProcessHeartbeatManager manager = manager(List.of(process));
        try {
            manager.startHeartbeat();
            assertTrue(awaitBeats(2));
            assertEquals(Long.valueOf(0L), process.getAckToken());
            assertEquals(ProcessState.IDLE, process.getCurrentState());

            // Core recovers, and the command lands on the next beat.
            responseBody = command("poller", "running", 4, 41);
            assertTrue(awaitAck(process, 4));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void aRejectedHeartbeatNeverStopsARunningProcess() throws Exception {
        CooperativeProcess process = named(new CooperativeProcess(), "poller", ProcessType.ENDLESS);
        responseBody = command("poller", "running", 1, 9);

        ProcessHeartbeatManager manager = manager(List.of(process));
        try {
            manager.startHeartbeat();
            assertTrue(awaitState(process, ProcessState.RUNNING));

            responseStatus = 503;
            responseBody = "nope";
            Thread.sleep(1_500);

            assertEquals(ProcessState.RUNNING, process.getCurrentState());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void aSlowCoreProducesNoCatchUpBurst() throws Exception {
        CooperativeProcess process = named(new CooperativeProcess(), "poller", ProcessType.ENDLESS);
        ProcessHeartbeatManager manager = manager(List.of(process));
        try {
            responseDelayMillis = 1_500; // slower than the one-second interval
            manager.startHeartbeat();
            assertTrue(awaitBeats(2));
            int afterSlow = heartbeatBodies.size();

            responseDelayMillis = 0;
            Thread.sleep(300);

            // A fixed-rate scheduler would fire every interval missed while Core was
            // slow, back to back, at a service that has only just recovered.
            int burst = heartbeatBodies.size() - afterSlow;
            assertTrue("expected at most one immediate beat, saw " + burst, burst <= 1);
        } finally {
            manager.shutdown();
        }
    }

    // ------------------------------------------------------------------ shutdown

    @Test
    public void shutdownStopsProcessesAndSendsOneFinalHeartbeat() throws Exception {
        CooperativeProcess process = named(new CooperativeProcess(), "poller", ProcessType.ENDLESS);
        responseBody = command("poller", "running", 1, 900);

        ProcessHeartbeatManager manager = manager(List.of(process));
        manager.startHeartbeat();
        assertTrue(awaitState(process, ProcessState.RUNNING));

        int before = heartbeatBodies.size();
        manager.shutdown();

        assertTrue("a final heartbeat was sent", heartbeatBodies.size() > before);
        String finalBeat = heartbeatBodies.get(heartbeatBodies.size() - 1);
        assertTrue(finalBeat, finalBeat.contains("\"currentState\":\"idle\""));
        assertEquals(ProcessState.IDLE, process.getCurrentState());
    }

    @Test
    public void theFinalHeartbeatResponseNeverStartsNewWork() throws Exception {
        CooperativeProcess process = named(new CooperativeProcess(), "poller", ProcessType.ENDLESS);
        ProcessHeartbeatManager manager = manager(List.of(process));
        manager.startHeartbeat();
        assertTrue(awaitBeats(1));

        // Core answers the final beat with a fresh start command.
        responseBody = command("poller", "running", 5, 555);
        manager.shutdown();
        Thread.sleep(200);

        assertEquals("no process was started by the final response", 0, process.runs.get());
        assertEquals(Long.valueOf(0L), process.getAckToken());
    }

    @Test
    public void shutdownIsIdempotentAndSendsExactlyOneFinalHeartbeat() throws Exception {
        CooperativeProcess process = named(new CooperativeProcess(), "poller", ProcessType.ENDLESS);
        ProcessHeartbeatManager manager = manager(List.of(process));
        manager.startHeartbeat();
        assertTrue(awaitBeats(1));

        int before = heartbeatBodies.size();
        manager.shutdown();
        manager.shutdown();
        manager.shutdown();

        assertEquals(1, heartbeatBodies.size() - before);
    }

    @Test
    public void aFailingFinalHeartbeatDoesNotFailShutdown() throws Exception {
        CooperativeProcess process = named(new CooperativeProcess(), "poller", ProcessType.ENDLESS);
        ProcessHeartbeatManager manager = manager(List.of(process));
        manager.startHeartbeat();
        assertTrue(awaitBeats(1));

        server.stop(0); // Core disappears mid-shutdown
        server = null;
        manager.shutdown(); // must not throw
    }

    @Test
    public void businessCodeThatIgnoresCancellationIsReportedRatherThanKilled() throws Exception {
        StubbornProcess process = named(new StubbornProcess(), "stubborn", ProcessType.ENDLESS);
        responseBody = command("stubborn", "running", 1, 42);

        ProcessHeartbeatManager manager = manager(List.of(process), Duration.ofMillis(300));
        manager.startHeartbeat();
        assertTrue(awaitState(process, ProcessState.RUNNING));

        long before = System.nanoTime();
        manager.shutdown();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        process.release.countDown();
        process.release2.countDown();

        assertTrue("shutdown waited " + elapsed + "ms", elapsed < 5_000);
    }

    // ------------------------------------------------------------------ helpers

    private static String command(String name, String desiredState, long token, long runId) {
        return "{\"processes\":[{\"name\":\"" + name + "\",\"desiredState\":\"" + desiredState
                + "\",\"desiredToken\":" + token + ",\"runId\":" + runId + "}]}";
    }

    private <T extends AbstractSynapsysProcess> T named(T process, String name, ProcessType type) {
        process.setName(name);
        process.setType(type);
        return process;
    }

    private boolean awaitBeats(int count) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            if (heartbeatBodies.size() >= count) return true;
            Thread.sleep(25);
        }
        return false;
    }

    private static boolean awaitState(AbstractSynapsysProcess process, ProcessState expected)
            throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            if (process.getCurrentState() == expected) return true;
            Thread.sleep(25);
        }
        return false;
    }

    private static boolean awaitAck(AbstractSynapsysProcess process, long token)
            throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            if (process.getAckToken() >= token) return true;
            Thread.sleep(25);
        }
        return false;
    }

    private static class CooperativeProcess extends AbstractEndlessProcess {
        private final AtomicInteger runs = new AtomicInteger();

        @Override
        protected void runForever(StopSignal stopSignal) throws Exception {
            runs.incrementAndGet();
            while (!stopSignal.isStopRequested()) {
                Thread.sleep(10);
            }
        }
    }

    /** Neither polls the stop signal nor honours an interrupt. */
    private static class StubbornProcess extends AbstractEndlessProcess {
        private final AtomicInteger runs = new AtomicInteger();
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch release2 = new CountDownLatch(1);

        @Override
        protected void runForever(StopSignal stopSignal) {
            CountDownLatch gate = runs.incrementAndGet() == 1 ? release : release2;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (gate.getCount() > 0 && System.nanoTime() < deadline) {
                try {
                    gate.await(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    Thread.interrupted();
                }
            }
        }
    }

    /** Drives CoreValidator through its real Client, to observe the health request. */
    private static final class CoreValidatorHarness {
        static void probe(String coreUrl) {
            com.synapsys.springboot.starter.config.SynapsysProcessProperties properties =
                    new com.synapsys.springboot.starter.config.SynapsysProcessProperties();
            properties.setCoreUrl(coreUrl);
            properties.setCoreToken("test-token");
            new CoreValidator(properties, new Client(
                    Duration.ofSeconds(2), Duration.ofSeconds(5), "test-token"))
                    .testCoreConnection(coreUrl);
        }
    }
}
