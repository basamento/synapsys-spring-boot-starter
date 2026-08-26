package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.client.Client;
import com.synapsys.springboot.starter.client.HttpResponseWrapper;
import com.synapsys.springboot.starter.model.HttpMethod;
import com.synapsys.springboot.starter.model.ProcessType;
import com.synapsys.springboot.starter.model.dto.request.HeartbeatProcessesRequestDTO;
import com.synapsys.springboot.starter.model.dto.request.ProcessInfoDTO;
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
            String coreToken,
            Duration connectTimeout,
            Duration requestTimeout
    ) {
        this.processes = processes;
        this.interval = interval;
        this.coreUrl = coreUrl;
        this.client = new Client(connectTimeout, requestTimeout, coreToken);
        this.workerName = workerName;
        this.host = (host == null || host.isBlank()) ? resolveLocalHost() : host;
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
        this.scheduler.scheduleAtFixedRate(this::processProcesses, 0, this.interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void processProcesses() {
        try {
            HeartbeatProcessesRequestDTO payload = this.getHeartbeatProcessesRequestDTO();
            String endpoint = ClientUtils.joinUrl(coreUrl, HEARTBEAT_ENDPOINT);

            HttpResponseWrapper<HeartbeatProcessesResponseDTO> response =
                    this.client.makeJsonCall(HttpMethod.POST, endpoint, payload, HeartbeatProcessesResponseDTO.class);

            this.processHeartbeatResponse(response);
        } catch (Exception e) {
            markCoreUnavailable("Failed to contact Core");
        }
    }

    private HeartbeatProcessesRequestDTO getHeartbeatProcessesRequestDTO() {
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
        return new HeartbeatProcessesRequestDTO(
                workerName,
                FRAMEWORK,
                host,
                processInfoDTOS,
                this.interval.toSeconds()
        );
    }

    private void processHeartbeatResponse(HttpResponseWrapper<HeartbeatProcessesResponseDTO> response) {
        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
            markCoreUnavailable("Heartbeat rejected by Core with status " + response.getStatusCode());
            return;
        }
        markCoreAvailable();

        HeartbeatProcessesResponseDTO payload = response.getBody();
        if (payload == null || payload.getProcesses() == null) return;

        for (ProcessInfoDTO coreUpdate : payload.getProcesses()) {
            AbstractSynapsysProcess process = this.findProcess(coreUpdate);

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

            // A token means "the local command handler accepted this command".
            // Do not acknowledge before handleStart/handleStop: if either throws,
            // Core must be able to deliver the command again on the next heartbeat.
            process.acknowledgeCoreCommand(decision.token());
        }
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

    private void markCoreUnavailable(String message) {
        if (coreAvailable) {
            coreAvailable = false;
            log.warn("{}{} | heartbeat retries will continue", SynapsysLog.PREFIX, message);
        } else {
            log.debug("{}{}", SynapsysLog.PREFIX, message);
        }
    }

    private void markCoreAvailable() {
        if (!coreAvailable) {
            coreAvailable = true;
            log.info("{}Core connection restored", SynapsysLog.PREFIX);
        }
    }

    private AbstractSynapsysProcess findProcess(ProcessInfoDTO processInfo) {
        return this.processes.stream()
                .filter(n -> n.getName().equals(processInfo.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No process found with name: " + processInfo.getName()
                ));
    }

    public void shutdown() {
        scheduler.shutdownNow();
        progressiveExecutors.values().forEach(ExecutorService::shutdownNow);
        progressiveExecutors.clear();
    }
}
