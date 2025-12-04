package com.synapsys.springboot.starter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import java.util.Map;

public class SynapsysNeuronProcessor implements ApplicationContextAware, InitializingBean {

    private ApplicationContext applicationContext;
    private static final Logger log = LoggerFactory.getLogger(SynapsysNeuronProcessor.class);

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.applicationContext = ctx;
    }

    @Override
    public void afterPropertiesSet() {
        log.info("SynapsysNeuronProcessor: validation started");
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(SynapsysNeuron.class);
        log.info("SynapsysNeuronProcessor: found {} Neurons", beans.size());
        for (Object bean : beans.values()) {
            log.info("SynapsysNeuronProcessor: processing bean {}", bean.getClass().getName());
            SynapsysNeuronContract neuron = this.validateAndCast(bean);
            this.processNeuron(neuron);
        }
        log.info("SynapsysNeuronProcessor: validation complete");
    }

    private SynapsysNeuronContract validateAndCast(Object bean) {
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
        log.info("SynapsysNeuronProcessor: processing Neuron '{}'", name);
        // 1. register the neuron on the core
    }

}
