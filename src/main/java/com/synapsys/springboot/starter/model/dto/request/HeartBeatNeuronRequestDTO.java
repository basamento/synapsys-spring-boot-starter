package com.synapsys.springboot.starter.model.dto.request;

import com.synapsys.springboot.starter.model.NeuronState;

public class HeartBeatNeuronRequestDTO {

    private String name;

    private NeuronState state;

    public HeartBeatNeuronRequestDTO(String name, NeuronState state) {
        this.name = name;
        this.state = state;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public NeuronState getState() {
        return state;
    }

    public void setState(NeuronState state) {
        this.state = state;
    }

}
