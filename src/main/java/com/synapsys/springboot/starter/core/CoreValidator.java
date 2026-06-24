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

    public String validateCoreUrl() {
        String coreUrl = properties.getCoreUrl();
        if (coreUrl == null || coreUrl.isBlank()) {
            throw new IllegalStateException("SynapsysNeuronProcessor: Missing 'synapsys.core-url' property in application.yml");
        }
        log.info("SynapsysNeuronProcessor: detected core-url: {}", coreUrl);
        return coreUrl;
    }

    public boolean testCoreConnection(String coreUrl) {
        String testUrl = ClientUtils.joinUrl(coreUrl, "api/health");
        log.info("SynapsysNeuronProcessor: testing connection to Core via {}", testUrl);
        int responseCode;
        try {
            responseCode = this.client.makeCallAndReturnResponseCode(HttpMethod.GET, testUrl);
        } catch (Exception e) {
            log.warn("SynapsysNeuronProcessor: failed to reach the Nucleus Core at {}", testUrl, e);
            return false;
        }
        if (responseCode < 200 || responseCode >= 300) {
            log.warn("SynapsysNeuronProcessor: Core responded with unexpected status on validating Core connection {}", responseCode);
            return false;
        }
        log.info("SynapsysNeuronProcessor: successfully connected to Core ({}).", responseCode);
        return true;
    }

}
