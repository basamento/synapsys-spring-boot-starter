package com.synapsys.springboot.starter.model.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.synapsys.springboot.starter.model.NeuronState;

@JsonIgnoreProperties(ignoreUnknown = true)
public class NeuronHeartBeatResponseDTO {

    private NeuronState neuronState;

    public NeuronHeartBeatResponseDTO() {}

    public NeuronState getDesiredState() {
        return neuronState;
    }

    public void setDesiredState(NeuronState neuronState) {
        this.neuronState = neuronState;
    }

}