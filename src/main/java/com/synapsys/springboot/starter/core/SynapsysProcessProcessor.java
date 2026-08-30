package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.client.Client;
import com.synapsys.springboot.starter.config.SynapsysProcessProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.net.URI;
import java.util.List;
import java.util.Locale;

public class SynapsysProcessProcessor implements ApplicationContextAware, InitializingBean, DisposableBean {

    private final CoreValidator coreValidator;
    private final Client client;
    private ApplicationContext applicationContext;
    private final SynapsysProcessProperties properties;
    private ProcessHeartbeatManager heartbeatManager;
    private volatile boolean destroyed = false;
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
        if (properties.getShutdownTimeout().isNegative()) {
            throw new IllegalStateException(
                    SynapsysLog.PREFIX + "'synapsys.shutdown-timeout' must not be negative"
            );
        }
        if (properties.getCoreToken() == null || properties.getCoreToken().isBlank()) {
            log.warn("{}Missing 'synapsys.core-token' property | Core will reject worker heartbeats", SynapsysLog.PREFIX);
        }
        // 1 validate connection with core
        String coreUrl = this.coreValidator.validateCoreUrl();
        warnAboutPlaintextToken(coreUrl);
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
                properties.getShutdownTimeout(),
                coreUrl,
                workerName,
                properties.getHost(),
                this.client,
                properties.isCaptureConsole()
        );
        // Echo the resolved configuration: makes a mis-set property visible at a glance.
        log.info("{}Worker '{}' registered {} process(es) | core={} host={} heartbeat={}",
                SynapsysLog.PREFIX,
                workerName,
                processes.size(),
                coreUrl,
                this.heartbeatManager.getHost(),
                properties.getHeartbeatInterval());
        if (!properties.isCaptureConsole()) {
            log.info("{}Console capture is disabled | process output stays local",
                    SynapsysLog.PREFIX);
        }
        this.heartbeatManager.startHeartbeat();
    }

    /**
     * A bearer token over plaintext HTTP is a credential leak, and the user has usually
     * not noticed. Loopback is exempt: that is ordinary local development.
     */
    private void warnAboutPlaintextToken(String coreUrl) {
        if (properties.getCoreToken() == null || properties.getCoreToken().isBlank()) return;
        try {
            URI uri = URI.create(coreUrl);
            if (!"http".equalsIgnoreCase(uri.getScheme())) return;
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean loopback = host.equals("localhost")
                    || host.equals("::1")
                    || host.equals("[::1]")
                    || host.startsWith("127.");
            if (loopback) return;
            log.warn("{}Core URL uses plaintext http and a token is set | the bearer token is "
                            + "sent in clear text to {}",
                    SynapsysLog.PREFIX, host);
        } catch (Exception e) {
            log.debug("{}Could not inspect the Core URL scheme", SynapsysLog.PREFIX, e);
        }
    }

    /**
     * Shutdown is idempotent and never throws: it runs during container teardown, where
     * an exception is at best noise and at worst masks the real cause.
     */
    @Override
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        try {
            if (heartbeatManager != null) {
                heartbeatManager.shutdown();
            }
        } catch (Throwable t) {
            log.debug("{}Unexpected failure during worker shutdown", SynapsysLog.PREFIX, t);
        }
        log.info("{}Worker stopped", SynapsysLog.PREFIX);
    }

}
