package com.synapsys.springboot.starter.model.dto.request;

public class HeartBeatNeuronRequestDTO {

    private String name;

    public HeartBeatNeuronRequestDTO(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

}
