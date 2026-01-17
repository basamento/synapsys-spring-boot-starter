package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.client.Client;
import com.synapsys.springboot.starter.client.HttpResponseWrapper;
import com.synapsys.springboot.starter.model.HttpMethod;
import com.synapsys.springboot.starter.model.NeuronType;
import com.synapsys.springboot.starter.model.dto.request.HeartBeatNeuronsRequestDTO;
import com.synapsys.springboot.starter.model.dto.request.NeuronInfoDTO;
import com.synapsys.springboot.starter.model.dto.response.HeartBeatNeuronsResponseDTO;
import com.synapsys.springboot.starter.utils.ClientUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class HeartBeater {

    private final Long interval;
    private final String coreUrl;
    private final Client client;
    private final List<AbstractSynapsysNeuron> neurons;

    private static final Logger log = LoggerFactory.getLogger(HeartBeater.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // One executor per PROGRESSIVE neuron (isolated execution)
    private final ConcurrentMap<String, ExecutorService> progressiveExecutors = new ConcurrentHashMap<>();

    private static final String HEART_BEAT_ENDPOINT = "api/neuron";
    private final String applicationName;

    public HeartBeater(
            List<AbstractSynapsysNeuron> neurons,
            Long interval,
            String coreUrl,
            String applicationName
    ) {
        this.neurons = neurons;
        this.interval = interval;
        this.coreUrl = coreUrl;
        this.client = new Client();
        this.applicationName = applicationName;
    }

    // Keep this constructor for compatibility; progressiveThreads is not used in per-neuron model
    public HeartBeater(
            List<AbstractSynapsysNeuron> neurons,
            Long interval,
            String coreUrl,
            String applicationName,
            int progressiveThreads
    ) {
        this(neurons, interval, coreUrl, applicationName);
    }

    public void startHeartBeat() {
        log.info("SynapsysNeuronProcessor: starting heart beating for {} neurons", neurons.size());
        this.scheduler.scheduleAtFixedRate(this::processNeurons, 0, this.interval, TimeUnit.MILLISECONDS);
    }

    private void processNeurons() {
        try {
            HeartBeatNeuronsRequestDTO payload = this.getHeartBeatNeuronsRequestDTO();
            String endpoint = ClientUtils.joinUrl(coreUrl, HEART_BEAT_ENDPOINT);

            HttpResponseWrapper<HeartBeatNeuronsResponseDTO> response =
                    this.client.makeJsonCall(HttpMethod.POST, endpoint, payload, HeartBeatNeuronsResponseDTO.class);

            this.processHeartBeatResponse(response);
        } catch (Exception e) {
            log.error("Heartbeat execution failed", e);
        }
    }

    private HeartBeatNeuronsRequestDTO getHeartBeatNeuronsRequestDTO() {
        List<NeuronInfoDTO> neuronInfoDTOS = new ArrayList<>();
        for (AbstractSynapsysNeuron neuron : this.neurons) {
            NeuronInfoDTO dto = new NeuronInfoDTO(
                    neuron.getName(),
                    neuron.getType(),
                    neuron.getCurrentState(),
                    neuron.getDesiredState()
            );
            dto.setAckToken(neuron.getAckToken());
            dto.setDesiredToken(neuron.getDesiredToken());
            dto.setRunId(neuron.getDesiredRunId());
            neuronInfoDTOS.add(dto);
        }
        return new HeartBeatNeuronsRequestDTO(applicationName, neuronInfoDTOS, this.interval);
    }

    private void processHeartBeatResponse(HttpResponseWrapper<HeartBeatNeuronsResponseDTO> response) {
        HeartBeatNeuronsResponseDTO payload = response.getBody();
        if (payload == null || payload.getNeurons() == null) return;

        for (NeuronInfoDTO coreUpdate : payload.getNeurons()) {
            AbstractSynapsysNeuron neuron = this.findNeuron(coreUpdate);

            AbstractSynapsysNeuron.CommandDecision decision = neuron.acceptCoreCommand(
                    coreUpdate.getDesiredState(),
                    coreUpdate.getDesiredToken(),
                    coreUpdate.getRunId()
            );

            if (decision.command() == AbstractSynapsysNeuron.Command.NONE) continue;

            if (decision.command() == AbstractSynapsysNeuron.Command.START) {
                log.info("Command START accepted for neuron {} (type={}, token={}, runId={})",
                        neuron.getName(), neuron.getType(), decision.token(), decision.runId());

                ExecutorService exec = executorFor(neuron);
                neuron.handleStart(decision.runId(), exec);

            } else if (decision.command() == AbstractSynapsysNeuron.Command.STOP) {
                log.info("Command STOP accepted for neuron {} (type={}, token={})",
                        neuron.getName(), neuron.getType(), decision.token());

                neuron.handleStop();
            }
        }
    }

    private ExecutorService executorFor(AbstractSynapsysNeuron neuron) {
        if (neuron.getType() != NeuronType.PROGRESSIVE) {
            return null;
        }

        // Dedicated single-thread executor per neuron ensures independence and no cross-blocking
        return progressiveExecutors.computeIfAbsent(neuron.getName(), name ->
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

    private AbstractSynapsysNeuron findNeuron(NeuronInfoDTO neuronInfo) {
        return this.neurons.stream()
                .filter(n -> n.getName().equals(neuronInfo.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No neuron found with name: " + neuronInfo.getName()
                ));
    }

    // Optional: allow graceful shutdown if your processor has lifecycle hooks
    public void shutdown() {
        scheduler.shutdownNow();
        progressiveExecutors.values().forEach(ExecutorService::shutdownNow);
        progressiveExecutors.clear();
    }
}
