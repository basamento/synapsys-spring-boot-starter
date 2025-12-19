package com.synapsys.springboot.starter.model.dto.request;

import java.util.List;

public class HeartBeatNeuronsRequestDTO {

    private String applicationName;
    private List<NeuronInfoDTO> neurons;

    public HeartBeatNeuronsRequestDTO() { }

    public HeartBeatNeuronsRequestDTO(String applicationName, List<NeuronInfoDTO> neurons) {
        this.applicationName = applicationName;
        this.neurons = neurons;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public List<NeuronInfoDTO> getNeurons() {
        return neurons;
    }

    public void setNeurons(List<NeuronInfoDTO> neurons) {
        this.neurons = neurons;
    }

}
