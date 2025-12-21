package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.client.Client;
import com.synapsys.springboot.starter.config.SynapsysNeuronProperties;
import com.synapsys.springboot.starter.model.HttpMethod;
import com.synapsys.springboot.starter.utils.ClientUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoreValidator {

    private final Client client;
    private final SynapsysNeuronProperties properties;
    private static final Logger log = LoggerFactory.getLogger(CoreValidator.class);

    public CoreValidator(SynapsysNeuronProperties properties) {
        this.client = new Client();
        this.properties = properties;
    }

    public String validateAndTestCoreUrl() {
        String coreUrl = properties.getCoreUrl();
        if (coreUrl == null || coreUrl.isBlank()) {
            throw new IllegalStateException("SynapsysNeuronProcessor: Missing 'synapsys.neuron.core-url' property in application.yml");
        }
        log.info("SynapsysNeuronProcessor: detected core-url: {}", coreUrl);
        String testUrl = ClientUtils.joinUrl(coreUrl, "api/health");
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
        return coreUrl;
    }

}
