package com.synapsys.springboot.starter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "synapsys")
public class SynapsysNeuronProperties {

    private String coreUrl;
    private String applicationName;
    private long heartbeatInterval = 5000;
    private final String springApplicationName;

    public SynapsysNeuronProperties(
            @Value("${spring.application.name:}") String springApplicationName) {
        this.springApplicationName = springApplicationName;
    }

    public String getCoreUrl() {
        return coreUrl;
    }

    public void setCoreUrl(String coreUrl) {
        this.coreUrl = coreUrl;
    }

    public String getApplicationName() {
        return (applicationName != null && !applicationName.isBlank())
                ? applicationName
                : springApplicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public long getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(long heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

}