package com.synapsys.springboot.starter.model.dto;

public class RegisterNeuronRequestDTO {

    private String name;

    public RegisterNeuronRequestDTO(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

}
