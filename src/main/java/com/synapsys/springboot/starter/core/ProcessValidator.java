package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.ProcessType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ProcessValidator {

    private static final Logger log = LoggerFactory.getLogger(ProcessValidator.class);

    public static List<AbstractSynapsysProcess> validateAndGetBeans(
            ApplicationContext applicationContext) {
        Map<String, Object> beans =
                applicationContext.getBeansWithAnnotation(SynapsysProcess.class);
        log.debug("{}Found {} @SynapsysProcess beans", SynapsysLog.PREFIX, beans.size());
        List<AbstractSynapsysProcess> validated = new ArrayList<>();
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();
            log.debug("{}Validating process bean: {}", SynapsysLog.PREFIX, bean.getClass().getName());
            validated.add(validateAndCast(bean, beanName));
        }
        return validated;
    }

    private static AbstractSynapsysProcess validateAndCast(Object bean, String beanName) {
        if (!(bean instanceof AbstractSynapsysProcess process)) {
            throw new IllegalStateException(
                    "@SynapsysProcess is only supported on classes extending "
                            + AbstractSynapsysProcess.class.getName()
                            + ". Offending class: "
                            + bean.getClass().getName()
            );
        }

        SynapsysProcess annotation = bean.getClass().getAnnotation(SynapsysProcess.class);
        String resolvedName = resolveProcessName(annotation, beanName);
        process.setName(resolvedName);

        // Decide type automatically (better than forcing ENDLESS)
        process.setType(resolveType(process));

        // Do NOT set desiredState/currentState here; base class initializes to IDLE already.
        return process;
    }

    private static ProcessType resolveType(AbstractSynapsysProcess process) {
        if (process instanceof AbstractProgressiveProcess) return ProcessType.PROGRESSIVE;
        if (process instanceof AbstractEndlessProcess) return ProcessType.ENDLESS;

        // fallback if someone extends the old base directly
        // you can either default, or throw to force correct inheritance
        return ProcessType.ENDLESS;
    }

    private static String resolveProcessName(SynapsysProcess annotation, String beanName) {
        return (annotation != null && !annotation.name().isBlank())
                ? annotation.name()
                : beanName;
    }

}
