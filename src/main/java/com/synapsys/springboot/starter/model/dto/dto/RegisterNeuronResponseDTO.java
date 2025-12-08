package com.synapsys.springboot.starter.model.dto.dto;

public class RegisterNeuronResponseDTO {

    private String name;
    private String status;

    public RegisterNeuronResponseDTO() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

}
