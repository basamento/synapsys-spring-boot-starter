package com.synapsys.springboot.starter.model.dto.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RegisterNeuronResponseDTO {

    private String status;

    public RegisterNeuronResponseDTO() {}

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

}