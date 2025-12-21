package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.NeuronState;
import com.synapsys.springboot.starter.model.NeuronType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class NeuronValidator {

    private static final Logger log = LoggerFactory.getLogger(NeuronValidator.class);

    public static List<AbstractSynapsysNeuron> validateAndGetBeans(
            ApplicationContext applicationContext) {
        Map<String, Object> beans =
                applicationContext.getBeansWithAnnotation(SynapsysNeuron.class);
        log.info("Found {} @SynapsysNeuron beans", beans.size());
        List<AbstractSynapsysNeuron> validated = new ArrayList<>();
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();
            log.info("Validating neuron bean: {}", bean.getClass().getName());
            validated.add(validateAndCast(bean, beanName));
        }
        return validated;
    }

    private static AbstractSynapsysNeuron validateAndCast(Object bean, String beanName) {
        if (!(bean instanceof AbstractSynapsysNeuron neuron)) {
            throw new IllegalStateException(
                    "@SynapsysNeuron is only supported on classes extending "
                            + AbstractSynapsysNeuron.class.getName()
                            + ". Offending class: "
                            + bean.getClass().getName()
            );
        }
        SynapsysNeuron annotation = bean.getClass().getAnnotation(SynapsysNeuron.class);
        String resolvedName = resolveNeuronName(annotation, beanName);
        neuron.setName(resolvedName);
        neuron.setType(NeuronType.ENDLESS);
        neuron.setCurrentState(NeuronState.IDLE);
        neuron.setDesiredState(NeuronState.IDLE);
        return neuron;
    }

    private static String resolveNeuronName(SynapsysNeuron annotation, String beanName) {
        return (annotation != null && !annotation.name().isBlank())
                ? annotation.name()
                : beanName;
    }

}
