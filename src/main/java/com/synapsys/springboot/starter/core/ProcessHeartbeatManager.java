package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.client.Client;
import com.synapsys.springboot.starter.client.HttpResponseWrapper;
import com.synapsys.springboot.starter.model.HttpMethod;
import com.synapsys.springboot.starter.model.ProcessState;
import com.synapsys.springboot.starter.model.ProcessType;
import com.synapsys.springboot.starter.model.dto.request.HeartbeatProcessesRequestDTO;
import com.synapsys.springboot.starter.model.dto.request.ProcessInfoDTO;
import com.synapsys.springboot.starter.model.dto.request.ProcessLogDTO;
import com.synapsys.springboot.starter.model.dto.response.HeartbeatProcessesResponseDTO;
import com.synapsys.springboot.starter.utils.ClientUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ProcessHeartbeatManager {

    private final Duration interval;
    private final Duration shutdownTimeout;
    private final String coreUrl;
    private final Client client;
    private final List<AbstractSynapsysProcess> processes;
    private final ProcessLogCapture logCapture;
    private volatile boolean coreAvailable = true;

    private static final Logger log = LoggerFactory.getLogger(ProcessHeartbeatManager.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "synapsys-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    // One executor per PROGRESSIVE process (isolated execution)
    private final ConcurrentMap<String, ExecutorService> progressiveExecutors = new ConcurrentHashMap<>();

    /** Shutdown runs at most once, however many times the container calls it. */
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);
    private volatile boolean stopped = false;

    private static final String HEARTBEAT_ENDPOINT = "api/v1/workers/heartbeat";

    /** Identifies which client library this worker runs, so Core does not have to guess. */
    private static final String FRAMEWORK = "springboot";

    private final String workerName;
    private final String host;

    public ProcessHeartbeatManager(
            List<AbstractSynapsysProcess> processes,
            Duration interval,
            String coreUrl,
            String workerName,
            String host,
            Client client
    ) {
        this(processes, interval, Duration.ofSeconds(10), coreUrl, workerName, host, client, true);
    }

    public ProcessHeartbeatManager(
            List<AbstractSynapsysProcess> processes,
            Duration interval,
            Duration shutdownTimeout,
            String coreUrl,
            String workerName,
            String host,
            Client client,
            boolean captureConsole
    ) {
        this.processes = processes;
        this.interval = interval;
        this.shutdownTimeout = shutdownTimeout;
        this.coreUrl = coreUrl;
        this.client = client;
        this.workerName = workerName;
        this.host = (host == null || host.isBlank()) ? resolveLocalHost() : host;
        // Console capture replaces System.out and System.err process-wide, so it is only
        // installed when the user has asked for it. Disabled, nothing is touched at all.
        this.logCapture = captureConsole ? new ProcessLogCapture() : null;
        if (this.logCapture != null) {
            this.logCapture.install();
            this.processes.forEach(process -> process.setLogCapture(this.logCapture));
        }
    }

    /**
     * Resolves the machine hostname once at startup. Returns null when it cannot be
     * determined, in which case Core falls back to the connection's remote address.
     */
    private static String resolveLocalHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.debug("{}Could not resolve local host name", SynapsysLog.PREFIX, e);
            return null;
        }
    }

    public String getHost() {
        return host;
    }

    /**
     * Schedules beats at a fixed <em>delay</em>, not a fixed rate.
     *
     * <p>scheduleAtFixedRate tries to keep up: a Core that answers slower than one
     * interval leaves a backlog that fires back to back the moment it recovers, which is
     * a burst aimed at a service that has just come back. A fixed delay cannot burst,
     * and — on this single-threaded scheduler — beats cannot overlap either.
     */
    public void startHeartbeat() {
        this.scheduler.scheduleWithFixedDelay(
                this::runHeartbeatTick, 0, this.interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * scheduleWithFixedDelay cancels a periodic task permanently as soon as one run
     * throws. Nothing may escape this method, or the worker goes silent for the rest of
     * its life.
     */
    private void runHeartbeatTick() {
        if (stopped) return;
        try {
            processProcesses(true);
        } catch (Throwable t) {
            log.error("{}Unexpected heartbeat failure | heartbeat retries will continue", SynapsysLog.PREFIX, t);
        }
    }

    private void processProcesses(boolean applyCommands) {
        List<ProcessLogDTO> logBatch = drainLogs();
        HttpResponseWrapper<HeartbeatProcessesResponseDTO> response;
        try {
            HeartbeatProcessesRequestDTO payload = this.getHeartbeatProcessesRequestDTO(logBatch);
            String endpoint = ClientUtils.joinUrl(coreUrl, HEARTBEAT_ENDPOINT);

            response = this.client.makeJsonCall(HttpMethod.POST, endpoint, payload, HeartbeatProcessesResponseDTO.class);
        } catch (Exception e) {
            requeueLogs(logBatch);
            markCoreUnavailable("Failed to contact Core", e);
            return;
        }

        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
            requeueLogs(logBatch);
            markCoreUnavailable("Heartbeat rejected by Core with status " + response.getStatusCode(), null);
            return;
        }

        // A 2xx whose body is not a readable heartbeat response is a protocol failure
        // rather than a quiet success: Core's contract makes `processes` required, so an
        // empty or unreadable body means this beat delivered no commands. Treating it as
        // success would acknowledge nothing and silently discard the drained logs.
        HeartbeatProcessesResponseDTO payload = response.getBody();
        if (payload == null || payload.getProcesses() == null) {
            requeueLogs(logBatch);
            markCoreUnavailable("Core returned a malformed heartbeat response", null);
            return;
        }

        markCoreAvailable();
        if (applyCommands) {
            this.processHeartbeatResponse(payload);
        }
    }

    private List<ProcessLogDTO> drainLogs() {
        return logCapture == null ? List.of() : logCapture.drain(100, 96_000);
    }

    private void requeueLogs(List<ProcessLogDTO> batch) {
        // Dropping these on a transient failure loses exactly the output the user needs.
        if (logCapture != null) logCapture.requeueFirst(batch);
    }

    private HeartbeatProcessesRequestDTO getHeartbeatProcessesRequestDTO(List<ProcessLogDTO> logs) {
        List<ProcessInfoDTO> processInfoDTOS = new ArrayList<>();
        for (AbstractSynapsysProcess process : this.processes) {
            // Only what the protocol asks a worker to report: name, type, currentState,
            // ackToken and runId. desiredState and desiredToken belong to Core, and
            // echoing this worker's copy of them back adds nothing it does not know.
            ProcessInfoDTO dto = new ProcessInfoDTO(
                    process.getName(),
                    process.getType(),
                    process.getCurrentState(),
                    null
            );
            dto.setAckToken(process.getAckToken());
            dto.setRunId(process.getDesiredRunId());
            processInfoDTOS.add(dto);
        }
        HeartbeatProcessesRequestDTO request = new HeartbeatProcessesRequestDTO(
                workerName,
                FRAMEWORK,
                host,
                processInfoDTOS,
                this.interval.toSeconds()
        );
        request.setLogs(logs);
        return request;
    }

    private void processHeartbeatResponse(HeartbeatProcessesResponseDTO payload) {
        for (ProcessInfoDTO coreUpdate : payload.getProcesses()) {
            if (coreUpdate == null || coreUpdate.getName() == null) continue;
            AbstractSynapsysProcess process = this.findProcess(coreUpdate);
            if (process == null) continue;
            try {
                this.applyCoreCommand(process, coreUpdate);
            } catch (Throwable e) {
                // One misbehaving process must not stop commands reaching the others,
                // nor end the heartbeat. The token stays unacknowledged, so Core
                // redelivers on the next beat.
                log.error("{}Command handling failed for process '{}'", SynapsysLog.PREFIX, process.getName(), e);
                process.setCurrentState(ProcessState.FAILED);
            }
        }
    }

    private void applyCoreCommand(AbstractSynapsysProcess process, ProcessInfoDTO coreUpdate) {
        AbstractSynapsysProcess.CommandDecision decision = process.acceptCoreCommand(
                coreUpdate.getDesiredState(),
                coreUpdate.getDesiredToken(),
                coreUpdate.getRunId()
        );

        AbstractSynapsysProcess.Outcome outcome = AbstractSynapsysProcess.Outcome.HANDLED;

        if (decision.command() == AbstractSynapsysProcess.Command.START) {
            log.info("{}Starting process '{}' (type={}, token={}, runId={})",
                    SynapsysLog.PREFIX,
                    process.getName(), process.getType(), decision.token(), decision.runId());
            if (decision.runId() == null) {
                log.warn("{}Core started process '{}' without a run id | its console output "
                                + "cannot be attached to this run",
                        SynapsysLog.PREFIX, process.getName());
            }

            ExecutorService exec = executorFor(process);
            outcome = process.handleStart(decision.runId(), exec);

        } else if (decision.command() == AbstractSynapsysProcess.Command.STOP) {
            log.info("{}Stopping process '{}' (type={}, token={})",
                    SynapsysLog.PREFIX,
                    process.getName(), process.getType(), decision.token());

            outcome = process.handleStop();
        }

        if (outcome == AbstractSynapsysProcess.Outcome.DEFERRED) {
            // The previous execution has not finished. Leaving the token unacknowledged
            // is the whole mechanism: Core redelivers the same command on a later beat,
            // and it is applied once this run is terminal. Acknowledging a deferred start
            // as a no-op would tell Core the run happened when nothing ran.
            log.debug("{}Deferring command for process '{}' (token={}) until the current "
                            + "execution finishes",
                    SynapsysLog.PREFIX, process.getName(), decision.token());
            return;
        }

        // Acknowledging means "the local handler accepted this command". It is reached
        // only when handleStart/handleStop returned normally: if either throws, the
        // caller records the failure and leaves the token unacknowledged, so Core
        // redelivers on the next heartbeat.
        process.acknowledgeCoreCommand(decision.token());
    }

    private ExecutorService executorFor(AbstractSynapsysProcess process) {
        if (process.getType() != ProcessType.PROGRESSIVE) {
            return null;
        }

        // Dedicated single-thread executor per process ensures independence and no cross-blocking
        return progressiveExecutors.computeIfAbsent(process.getName(), name ->
                Executors.newSingleThreadExecutor(new ThreadFactory() {
                    private final AtomicInteger idx = new AtomicInteger(0);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r);
                        t.setName("synapsys-prog-" + name + "-" + idx.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                })
        );
    }

    private void markCoreUnavailable(String message, Exception cause) {
        if (coreAvailable) {
            coreAvailable = false;
            // The cause is logged with the transition: without it "Failed to contact Core"
            // gives no way to tell a DNS failure from a timeout or a TLS problem.
            log.warn("{}{} | heartbeat retries will continue", SynapsysLog.PREFIX, message, cause);
        } else {
            log.debug("{}{}", SynapsysLog.PREFIX, message, cause);
        }
    }

    private void markCoreAvailable() {
        if (!coreAvailable) {
            coreAvailable = true;
            log.info("{}Core connection restored", SynapsysLog.PREFIX);
        }
    }

    /**
     * Returns null when Core reports a process this worker does not declare, for example
     * after a rename. Skipping is correct: the worker cannot act on it either way.
     */
    private AbstractSynapsysProcess findProcess(ProcessInfoDTO processInfo) {
        AbstractSynapsysProcess match = this.processes.stream()
                .filter(n -> n.getName().equals(processInfo.getName()))
                .findFirst()
                .orElse(null);
        if (match == null) {
            log.warn("{}Core sent a command for unknown process '{}' | ignoring",
                    SynapsysLog.PREFIX, processInfo.getName());
        }
        return match;
    }

    /**
     * Bounded cooperative shutdown, in this order:
     *
     * <ol>
     *   <li>the heartbeat loop stops, and no further beat can start;</li>
     *   <li>every running process is asked to stop;</li>
     *   <li>the starter waits up to {@code synapsys.shutdown-timeout} for them to become
     *       terminal;</li>
     *   <li><strong>one</strong> best-effort final heartbeat carries the final process
     *       states and any queued log lines, and the commands in its response are
     *       ignored, because shutdown must never start new work;</li>
     *   <li>console capture is uninstalled and the per-process executors are released.</li>
     * </ol>
     *
     * <p>Idempotent and non-throwing: it runs during container teardown, where an
     * exception is at best noise and at worst masks the real cause.
     */
    public void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) return;
        stopped = true;
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();

        // 1. No further beats. Done first, so the loop cannot race the final one.
        try {
            scheduler.shutdownNow();
            scheduler.awaitTermination(remainingMillis(deadline), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log.debug("{}Could not stop the heartbeat scheduler cleanly", SynapsysLog.PREFIX, t);
        }

        // 2. Ask every process to stop. One that throws must not skip the others.
        for (AbstractSynapsysProcess process : processes) {
            try {
                process.handleStop();
            } catch (Throwable t) {
                log.error("{}Could not stop process '{}' during shutdown",
                        SynapsysLog.PREFIX, process.getName(), t);
            }
        }

        // 3. Wait, but never past the caller's deadline. Business logic that ignores its
        //    stop signal is reported and left running: the starter cannot safely kill a
        //    thread, and a replacement execution can no longer overlap it either way.
        for (AbstractSynapsysProcess process : processes) {
            try {
                if (!process.settle(remainingMillis(deadline))) {
                    log.warn("{}Process '{}' did not stop before the shutdown timeout | it is "
                                    + "still running and will not be replaced by a new execution",
                            SynapsysLog.PREFIX, process.getName());
                }
            } catch (Throwable t) {
                log.debug("{}Could not wait for process '{}'", SynapsysLog.PREFIX, process.getName(), t);
            }
        }

        // 4. One bounded, best-effort final snapshot. Its response is not applied, and a
        //    failure is diagnostic only: shutdown never fails or extends for it. Skipped
        //    when the budget is already spent, so it cannot run past the deadline.
        if (remainingMillis(deadline) > 0) {
            try {
                processProcesses(false);
            } catch (Throwable t) {
                log.debug("{}Final heartbeat failed", SynapsysLog.PREFIX, t);
            }
        } else {
            log.debug("{}Shutdown budget exhausted | skipping the final heartbeat", SynapsysLog.PREFIX);
        }

        // 5. Only now: everything printed during the stop sequence had a chance to reach
        //    Core on the final beat.
        try {
            if (logCapture != null) logCapture.uninstall();
        } catch (Throwable t) {
            log.debug("{}Could not restore the console streams", SynapsysLog.PREFIX, t);
        }
        progressiveExecutors.values().forEach(executor -> {
            try {
                executor.shutdownNow();
            } catch (Throwable t) {
                log.debug("{}Could not stop a progressive executor", SynapsysLog.PREFIX, t);
            }
        });
        progressiveExecutors.clear();
    }

    private static long remainingMillis(long deadlineNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }
}
