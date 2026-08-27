package com.synapsys.springboot.starter.auto;

import com.synapsys.springboot.starter.config.SynapsysProcessProperties;
import com.synapsys.springboot.starter.core.SynapsysProcessProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SynapsysProcessProperties.class)
@ConditionalOnProperty(prefix = "synapsys", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SynapsysProcessAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean
    public SynapsysProcessProcessor synapsysProcessProcessor(
            ApplicationContext ctx,
            SynapsysProcessProperties properties
    ) {
        return new SynapsysProcessProcessor(ctx, properties);
    }
}
