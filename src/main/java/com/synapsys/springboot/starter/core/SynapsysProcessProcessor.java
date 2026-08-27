package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.client.Client;
import com.synapsys.springboot.starter.config.SynapsysProcessProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import java.util.List;

public class SynapsysProcessProcessor implements ApplicationContextAware, InitializingBean, DisposableBean {

    private final CoreValidator coreValidator;
    private final Client client;
    private ApplicationContext applicationContext;
    private final SynapsysProcessProperties properties;
    private ProcessHeartbeatManager heartbeatManager;
    private static final Logger log = LoggerFactory.getLogger(SynapsysProcessProcessor.class);

    public SynapsysProcessProcessor(ApplicationContext applicationContext, SynapsysProcessProperties properties) {
        this.applicationContext = applicationContext;
        this.properties = properties;
        // One HTTP client for the whole starter: startup validation and the heartbeat
        // share a connection pool instead of each holding their own.
        this.client = new Client(
                properties.getConnectTimeout(),
                properties.getRequestTimeout(),
                properties.getCoreToken()
        );
        this.coreValidator = new CoreValidator(this.properties, this.client);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.getHeartbeatInterval().isNegative()
                || properties.getHeartbeatInterval().isZero()
                || properties.getHeartbeatInterval().toMillis() % 1000 != 0) {
            throw new IllegalStateException(
                    SynapsysLog.PREFIX + "'synapsys.heartbeat-interval' must be a positive whole number of seconds"
            );
        }
        if (properties.getCoreToken() == null || properties.getCoreToken().isBlank()) {
            log.warn("{}Missing 'synapsys.core-token' property | Core will reject worker heartbeats", SynapsysLog.PREFIX);
        }
        // 1 validate connection with core
        String coreUrl = this.coreValidator.validateCoreUrl();
        boolean coreReachable = this.coreValidator.testCoreConnection(coreUrl);
        if (!coreReachable && properties.isFailFast()) {
            throw new IllegalStateException(SynapsysLog.PREFIX + "Core is unreachable at startup: " + coreUrl);
        }
        if (!coreReachable) {
            log.warn("{}Core is unreachable at {} | heartbeat retries will continue",
                    SynapsysLog.PREFIX, coreUrl);
        }
        List<AbstractSynapsysProcess> processes = ProcessValidator.validateAndGetBeans(this.applicationContext);
        String workerName = properties.getWorkerName();
        if (workerName == null || workerName.isBlank()) {
            throw new IllegalStateException(
                    SynapsysLog.PREFIX + "Missing 'synapsys.worker-name' property and no 'spring.application.name' fallback is available"
            );
        }
        this.heartbeatManager = new ProcessHeartbeatManager(
                processes,
                properties.getHeartbeatInterval(),
                coreUrl,
                workerName,
                properties.getHost(),
                this.client
        );
        // Echo the resolved configuration: makes a mis-set property visible at a glance.
        log.info("{}Worker '{}' registered {} process(es) | core={} host={} heartbeat={}",
                SynapsysLog.PREFIX,
                workerName,
                processes.size(),
                coreUrl,
                this.heartbeatManager.getHost(),
                properties.getHeartbeatInterval());
        this.heartbeatManager.startHeartbeat();
    }

    @Override
    public void destroy() {
        if (heartbeatManager != null) {
            heartbeatManager.shutdown();
        }
        log.info("{}Worker stopped", SynapsysLog.PREFIX);
    }

}
