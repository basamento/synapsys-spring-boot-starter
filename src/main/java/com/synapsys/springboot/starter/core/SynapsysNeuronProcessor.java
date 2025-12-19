package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.config.SynapsysNeuronProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import java.util.List;

public class SynapsysNeuronProcessor implements ApplicationContextAware, InitializingBean {

    private final CoreValidator coreValidator;
    private ApplicationContext applicationContext;
    private final SynapsysNeuronProperties properties;
    private static final Logger log = LoggerFactory.getLogger(SynapsysNeuronProcessor.class);

    public SynapsysNeuronProcessor(ApplicationContext applicationContext, SynapsysNeuronProperties properties) {
        this.applicationContext = applicationContext;
        this.properties = properties;
        this.coreValidator = new CoreValidator(this.properties);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        // 1 validate connection with core
        String coreUrl = this.coreValidator.validateAndTestCoreUrl();
        // 2 get and validate all the beans
        List<AbstractSynapsysNeuron> neurons = NeuronValidator.validateAndGetBeans(this.applicationContext);
        // 3 starts the heartbeat
        Long interval = properties.getHeartbeatInterval();
        String applicationName = properties.getApplicationName();
        HeartBeater heartBeater = new HeartBeater(neurons, interval, coreUrl, applicationName);
        heartBeater.startHeartBeat();
    }

}
