package com.synapsys.springboot.starter.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

@ConfigurationProperties(prefix = "synapsys")
public class SynapsysNeuronProperties {

    private String coreUrl;
    private String applicationName;
    private long heartbeatInterval = 5000;
    private boolean failFast = true;
    @Autowired
    private Environment environment;

    public SynapsysNeuronProperties() {}

    public String getCoreUrl() {
        return coreUrl;
    }

    public void setCoreUrl(String coreUrl) {
        this.coreUrl = coreUrl;
    }

    public String getApplicationName() {
        if (applicationName != null && !applicationName.isBlank()) {
            return applicationName;
        }
        return environment.getProperty("spring.application.name");
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

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

}
