package com.synapsys.springboot.starter.auto;

import com.synapsys.springboot.starter.config.SynapsysNeuronProperties;
import com.synapsys.springboot.starter.core.SynapsysNeuronProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SynapsysNeuronProperties.class)
public class SynapsysNeuronAutoConfiguration {
    
    @Bean
    public SynapsysNeuronProcessor synapsysNeuronProcessor(
            ApplicationContext ctx,
            SynapsysNeuronProperties properties
    ) {
        return new SynapsysNeuronProcessor(ctx, properties);
    }
}
