package com.synapsys.springboot.starter;

import com.synapsys.springboot.starter.client.Client;
import com.synapsys.springboot.starter.client.HttpResponseWrapper;
import com.synapsys.springboot.starter.config.SynapsysNeuronProperties;
import com.synapsys.springboot.starter.model.NeuronState;
import com.synapsys.springboot.starter.model.HttpMethod;
import com.synapsys.springboot.starter.model.dto.request.HeartBeatNeuronRequestDTO;
import com.synapsys.springboot.starter.model.dto.request.RegisterNeuronRequestDTO;
import com.synapsys.springboot.starter.model.dto.response.NeuronHeartBeatResponseDTO;
import com.synapsys.springboot.starter.model.dto.response.RegisterNeuronResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class SynapsysNeuronProcessor implements ApplicationContextAware, InitializingBean {

    private String coreUrl;
    private final Client client;
    private ApplicationContext applicationContext;
    private final SynapsysNeuronProperties properties;
    private static final Logger log = LoggerFactory.getLogger(SynapsysNeuronProcessor.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService neuronExecutor = Executors.newCachedThreadPool();
    private final Map<String, NeuronRuntime> runtimes = new ConcurrentHashMap<>();

    public SynapsysNeuronProcessor(ApplicationContext ctx, SynapsysNeuronProperties properties) {
        this.applicationContext = ctx;
        this.properties = properties;
        this.client = new Client();
    }

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.applicationContext = ctx;
    }

    @Override
    public void afterPropertiesSet() {
        log.info("SynapsysNeuronProcessor: validation started");
        this.validateAndTestCoreUrl();
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(SynapsysNeuron.class);
        log.info("SynapsysNeuronProcessor: found {} Neurons", beans.size());
        for (Object bean : beans.values()) {
            log.info("SynapsysNeuronProcessor: processing bean {}", bean.getClass().getName());
            SynapsysNeuronContract neuron = this.validateAndCastBean(bean);
            this.processNeuron(neuron);
        }
        log.info("SynapsysNeuronProcessor: validation complete");
    }

    private void validateAndTestCoreUrl() {
        String coreUrl = properties.getCoreUrl();
        if (coreUrl == null || coreUrl.isBlank()) {
            throw new IllegalStateException("SynapsysNeuronProcessor: Missing 'synapsys.neuron.core-url' property in application.yml");
        }
        this.coreUrl = coreUrl;
        log.info("SynapsysNeuronProcessor: detected core-url: {}", coreUrl);
        String testUrl = this.joinUrl(coreUrl, "api/test");
        log.info("SynapsysNeuronProcessor: testing connection to Core via {}", testUrl);
        int responseCode;
        try {
            responseCode = this.client.makeCallAndReturnResponseCode(HttpMethod.GET, testUrl);
        } catch (Exception e) {
            throw new RuntimeException("SynapsysNeuronProcessor: failed to reach the Nucleus Core at " + testUrl, e);
        }
        if (responseCode < 200 || responseCode >= 300) {
            throw new IllegalStateException("SynapsysNeuronProcessor: Core responded with unexpected status on validating Core connection " + responseCode);
        }
        log.info("SynapsysNeuronProcessor: successfully connected to Core ({}).", responseCode);
    }

    private SynapsysNeuronContract validateAndCastBean(Object bean) {
        if (!(bean instanceof SynapsysNeuronContract contract)) {
            throw new IllegalStateException(
                    "Class " + bean.getClass().getName() +
                            " is annotated with @SynapsysNeuron but does NOT implement SynapsysNeuronContract"
            );
        }
        return contract;
    }

    private void processNeuron(SynapsysNeuronContract neuron) {
        String name = neuron.getName();
        runtimes.putIfAbsent(name, new NeuronRuntime());
        log.info("SynapsysNeuronProcessor: processing Neuron '{}'", name);
        // 1. register the neuron on the core
        String registerUrl = this.joinUrl(this.coreUrl, "api/neurons");
        long pingTiming = properties.getHeartbeatIntervalMs();
        RegisterNeuronRequestDTO body = new RegisterNeuronRequestDTO(name, pingTiming);
        HttpResponseWrapper<RegisterNeuronResponseDTO> response =
                this.client.makeJsonCall(HttpMethod.POST, registerUrl, body, RegisterNeuronResponseDTO.class);
        int responseCode = response.getStatusCode();
        if (responseCode < 200 || responseCode >= 300) {
            throw new IllegalStateException("SynapsysNeuronProcessor: Core responded with status " + responseCode + " on registering Neuron: " + name);
        } else if (responseCode == 201) {
            log.info("SynapsysNeuronProcessor: successfully registered Neuron {}", neuron);
            this.startHeartbeat(neuron);
        } else {
            log.info("SynapsysNeuronProcessor: unsuccessfully registered Neuron {}", neuron);
        }
    }

    private void startHeartbeat(SynapsysNeuronContract neuron) {
        long interval = properties.getHeartbeatIntervalMs();
        String name = neuron.getName();
        String heartbeatUrl = joinUrl(coreUrl, "api/neurons");
        NeuronRuntime runtime = runtimes.get(name);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                HeartBeatNeuronRequestDTO body = new HeartBeatNeuronRequestDTO(name, runtime.state.get());
                HttpResponseWrapper<NeuronHeartBeatResponseDTO> response =
                        client.makeJsonCall(
                                HttpMethod.PUT,
                                heartbeatUrl,
                                body,
                                NeuronHeartBeatResponseDTO.class
                        );
                NeuronState desiredState = response.getBody().getDesiredState();
                // only start if core wants it AND we're idle
                if (desiredState == NeuronState.RUNNING && runtime.state.compareAndSet(NeuronState.IDLE, NeuronState.RUNNING)) {
                    neuronExecutor.submit(() -> {
                        try {
                            neuron.start();
                        } catch (Exception e) {
                            log.error("Neuron '{}' failed during start()", name, e);
                        } finally {
                            runtime.state.set(NeuronState.IDLE);
                        }
                    });
                }
            } catch (Exception e) {
                log.warn("SynapsysNeuronProcessor: heartbeat error for neuron '{}': {}", name, e.getMessage());
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    private String joinUrl(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Base URL cannot be null or empty");
        }
        if (path == null) path = "";
        path = path.startsWith("/") ? path.substring(1) : path;
        baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        return baseUrl + path;
    }

    private static class NeuronRuntime {
        final AtomicReference<NeuronState> state =
                new AtomicReference<>(NeuronState.IDLE);
    }


}
