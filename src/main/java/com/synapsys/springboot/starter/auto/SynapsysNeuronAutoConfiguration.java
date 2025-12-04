package com.synapsys.springboot.starter.auto;

import com.synapsys.springboot.starter.SynapsysNeuronProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SynapsysNeuronAutoConfiguration {

    @Bean
    public SynapsysNeuronProcessor synapsysNeuronProcessor() {
        return new SynapsysNeuronProcessor();
    }

}
