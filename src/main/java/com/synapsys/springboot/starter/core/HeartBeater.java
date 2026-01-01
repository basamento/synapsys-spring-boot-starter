package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.client.Client;
import com.synapsys.springboot.starter.client.HttpResponseWrapper;
import com.synapsys.springboot.starter.model.HttpMethod;
import com.synapsys.springboot.starter.model.NeuronState;
import com.synapsys.springboot.starter.model.NeuronType;
import com.synapsys.springboot.starter.model.dto.request.HeartBeatNeuronsRequestDTO;
import com.synapsys.springboot.starter.model.dto.request.NeuronInfoDTO;
import com.synapsys.springboot.starter.model.dto.response.HeartBeatNeuronsResponseDTO;
import com.synapsys.springboot.starter.utils.ClientUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HeartBeater {

    private final Long interval;
    private final String coreUrl;
    private final Client client;
    private final List<AbstractSynapsysNeuron> neurons;
    private static final Logger log = LoggerFactory.getLogger(HeartBeater.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final String HEART_BEAT_ENDPOINT = "api/neuron";
    private final String applicationName;

    public HeartBeater(List<AbstractSynapsysNeuron> neurons, Long interval, String coreUrl, String applicationName) {
        this.neurons = neurons;
        this.interval = interval;
        this.coreUrl = coreUrl;
        this.client = new Client();
        this.applicationName = applicationName;
    }

    public void startHeartBeat() {
        log.info("SynapsysNeuronProcessor: starting heart beating for {} neurons", neurons.size());
        this.scheduler.scheduleAtFixedRate(this::processNeurons, 0, this.interval, TimeUnit.MILLISECONDS);
    }

    private void processNeurons() {
        try {
            log.info("SynapsysNeuronProcessor: heartbeat on scheduled time");
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
            NeuronState currentState = neuron.getCurrentState();
            NeuronState desiredState = neuron.getDesiredState();
            NeuronType type = neuron.getType();
            NeuronInfoDTO neuronInfoDTO = new NeuronInfoDTO(neuron.getName(), type, currentState, desiredState);
            neuronInfoDTOS.add(neuronInfoDTO);
        }
        return new HeartBeatNeuronsRequestDTO(applicationName, neuronInfoDTOS, this.interval);
    }

    private void processHeartBeatResponse(HttpResponseWrapper<HeartBeatNeuronsResponseDTO> response) {
        HeartBeatNeuronsResponseDTO payload = response.getBody();
        for (NeuronInfoDTO neuronUpdateState : payload.getNeurons()) {
            AbstractSynapsysNeuron neuron = this.findNeuron(neuronUpdateState);
            NeuronState newDesiredState = this.computeNeuronState(neuron, neuronUpdateState);
            this.handleNeuronExecution(neuron, newDesiredState);
        }
    }

    private AbstractSynapsysNeuron findNeuron(NeuronInfoDTO neuronInfo) {
        return this.neurons.stream()
                .filter(n -> n.getName().equals(neuronInfo.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No neuron found with name: " + neuronInfo.getName()
                ));
    }

    private NeuronState computeNeuronState(AbstractSynapsysNeuron neuron, NeuronInfoDTO neuronInfo) {
        NeuronState current = neuron.getCurrentState();
        NeuronState desired = neuronInfo.getDesiredState();
        String neuronName = neuron.getName();
        if (current == NeuronState.IDLE) {
            if (desired == NeuronState.RUNNING) {
                log.debug("Neuron '{}' transitioning IDLE → RUNNING", neuronName);
                return NeuronState.RUNNING;
            }
            if (desired == NeuronState.IDLE) {
                log.debug("Neuron '{}' remains IDLE", neuronName);
                return NeuronState.IDLE;
            }
        }
        if (current == NeuronState.RUNNING) {
            if (desired == NeuronState.IDLE) {
                log.debug("Neuron '{}' transitioning RUNNING → IDLE", neuronName);
                return NeuronState.IDLE;
            }
            if (desired == NeuronState.RUNNING) {
                log.debug("Neuron '{}' remains RUNNING", neuronName);
                return NeuronState.RUNNING;
            }
        }
        if (current == NeuronState.STOPPING) {
            if (desired == NeuronState.IDLE) {
                log.debug("Neuron '{}' is STOPPING and awaiting transition to IDLE", neuronName);
                return NeuronState.STOPPING;
            }
        }
        String message = String.format("Illegal neuron state transition for '%s': %s → %s", neuronName, current, desired);
        log.error(message);
        throw new IllegalStateException(message);
    }

    private void handleNeuronExecution(AbstractSynapsysNeuron neuron, NeuronState newDesiredState) {
        if (neuron.getCurrentState() == newDesiredState) return;
        neuron.setDesiredState(newDesiredState);
        if (neuron.getCurrentState() == NeuronState.IDLE && newDesiredState == NeuronState.RUNNING) {
            log.info("Starting neuron {}", neuron.getName());
            neuron.start();
        } else if (neuron.getCurrentState() == NeuronState.RUNNING && newDesiredState == NeuronState.IDLE) {
            log.info("Stopping neuron {}", neuron.getName());
            neuron.stop();
        }
    }

}
