package com.synapsys.springboot.starter.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import java.time.Duration;

/**
 * Binds the {@code synapsys.*} configuration.
 *
 * <p>{@code ignoreUnknownFields = false} is deliberate: a typo such as
 * {@code synapsys.heartbeat-interval-ms} would otherwise be accepted and silently
 * ignored, leaving the worker running on defaults with no indication why.
 */
@ConfigurationProperties(prefix = "synapsys", ignoreUnknownFields = false)
public class SynapsysProcessProperties {

    private String coreUrl;
    private String workerName;
    private String host;
    private Duration heartbeatInterval = Duration.ofSeconds(5);
    private boolean failFast = false;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(5);
    @Autowired
    private Environment environment;

    public SynapsysProcessProperties() {}

    public String getCoreUrl() {
        return coreUrl;
    }

    public void setCoreUrl(String coreUrl) {
        this.coreUrl = coreUrl;
    }

    public String getWorkerName() {
        if (workerName != null && !workerName.isBlank()) {
            return workerName;
        }
        return environment == null ? null : environment.getProperty("spring.application.name");
    }

    public void setWorkerName(String workerName) {
        this.workerName = workerName;
    }

    /**
     * Host reported to Core. Defaults to the machine hostname; set it explicitly when
     * the resolved name is meaningless, as with a container id.
     */
    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        if (heartbeatInterval != null) {
            this.heartbeatInterval = heartbeatInterval;
        }
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        if (connectTimeout != null) {
            this.connectTimeout = connectTimeout;
        }
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        if (requestTimeout != null) {
            this.requestTimeout = requestTimeout;
        }
    }

}
