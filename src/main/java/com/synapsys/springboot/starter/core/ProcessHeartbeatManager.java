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
import java.util.concurrent.atomic.AtomicInteger;

public class ProcessHeartbeatManager {

    private final Duration interval;
    private final String coreUrl;
    private final Client client;
    private final List<AbstractSynapsysProcess> processes;
    private final ProcessLogCapture logCapture = new ProcessLogCapture();
    private volatile boolean coreAvailable = true;

    private static final Logger log = LoggerFactory.getLogger(ProcessHeartbeatManager.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // One executor per PROGRESSIVE process (isolated execution)
    private final ConcurrentMap<String, ExecutorService> progressiveExecutors = new ConcurrentHashMap<>();

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
        this.processes = processes;
        this.interval = interval;
        this.coreUrl = coreUrl;
        this.client = client;
        this.workerName = workerName;
        this.host = (host == null || host.isBlank()) ? resolveLocalHost() : host;
        this.logCapture.install();
        this.processes.forEach(process -> process.setLogCapture(this.logCapture));
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

    public void startHeartbeat() {
        this.scheduler.scheduleAtFixedRate(this::runHeartbeatTick, 0, this.interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * scheduleAtFixedRate cancels a periodic task permanently as soon as one run throws.
     * Nothing may escape this method, or the worker goes silent for the rest of its life.
     */
    private void runHeartbeatTick() {
        try {
            processProcesses();
        } catch (Throwable t) {
            log.error("{}Unexpected heartbeat failure | heartbeat retries will continue", SynapsysLog.PREFIX, t);
        }
    }

    private void processProcesses() {
        List<ProcessLogDTO> logBatch = logCapture.drain(100, 96_000);
        HttpResponseWrapper<HeartbeatProcessesResponseDTO> response;
        try {
            HeartbeatProcessesRequestDTO payload = this.getHeartbeatProcessesRequestDTO(logBatch);
            String endpoint = ClientUtils.joinUrl(coreUrl, HEARTBEAT_ENDPOINT);

            response = this.client.makeJsonCall(HttpMethod.POST, endpoint, payload, HeartbeatProcessesResponseDTO.class);
        } catch (Exception e) {
            logCapture.requeueFirst(logBatch);
            markCoreUnavailable("Failed to contact Core", e);
            return;
        }

        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
            logCapture.requeueFirst(logBatch);
            markCoreUnavailable("Heartbeat rejected by Core with status " + response.getStatusCode(), null);
            return;
        }
        markCoreAvailable();
        this.processHeartbeatResponse(response);
    }

    private HeartbeatProcessesRequestDTO getHeartbeatProcessesRequestDTO(List<ProcessLogDTO> logs) {
        List<ProcessInfoDTO> processInfoDTOS = new ArrayList<>();
        for (AbstractSynapsysProcess process : this.processes) {
            ProcessInfoDTO dto = new ProcessInfoDTO(
                    process.getName(),
                    process.getType(),
                    process.getCurrentState(),
                    process.getDesiredState()
            );
            dto.setAckToken(process.getAckToken());
            dto.setDesiredToken(process.getDesiredToken());
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

    private void processHeartbeatResponse(HttpResponseWrapper<HeartbeatProcessesResponseDTO> response) {
        HeartbeatProcessesResponseDTO payload = response.getBody();
        if (payload == null || payload.getProcesses() == null) return;

        for (ProcessInfoDTO coreUpdate : payload.getProcesses()) {
            AbstractSynapsysProcess process = this.findProcess(coreUpdate);
            if (process == null) continue;
            try {
                this.applyCoreCommand(process, coreUpdate);
            } catch (Exception e) {
                // One misbehaving process must not stop commands reaching the others,
                // nor end the heartbeat.
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

        if (decision.command() == AbstractSynapsysProcess.Command.START) {
            log.info("{}Starting process '{}' (type={}, token={}, runId={})",
                    SynapsysLog.PREFIX,
                    process.getName(), process.getType(), decision.token(), decision.runId());

            ExecutorService exec = executorFor(process);
            process.handleStart(decision.runId(), exec);

        } else if (decision.command() == AbstractSynapsysProcess.Command.STOP) {
            log.info("{}Stopping process '{}' (type={}, token={})",
                    SynapsysLog.PREFIX,
                    process.getName(), process.getType(), decision.token());

            process.handleStop();
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

    public void shutdown() {
        logCapture.uninstall();
        scheduler.shutdownNow();
        progressiveExecutors.values().forEach(ExecutorService::shutdownNow);
        progressiveExecutors.clear();
    }
}
