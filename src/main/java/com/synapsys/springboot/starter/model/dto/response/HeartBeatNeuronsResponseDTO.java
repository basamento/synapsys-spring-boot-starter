package com.synapsys.springboot.starter.model.dto.response;

import com.synapsys.springboot.starter.model.dto.request.NeuronInfoDTO;
import java.util.List;

public class HeartBeatNeuronsResponseDTO {

    List<NeuronInfoDTO> neurons;

    public List<NeuronInfoDTO> getNeurons() {
        return neurons;
    }

    public void setNeurons(List<NeuronInfoDTO> neurons) {
        this.neurons = neurons;
    }
}