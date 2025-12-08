package com.synapsys.springboot.starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "synapsys.neuron")
public class SynapsysNeuronProperties {

    /**
     * URL of the Core service where neurons must be registered.
     */
    private String coreUrl;

    public String getCoreUrl() {
        return coreUrl;
    }

    public void setCoreUrl(String coreUrl) {
        this.coreUrl = coreUrl;
    }

}