package com.synapsys.springboot.starter.model.dto.response;

import com.synapsys.springboot.starter.model.dto.request.NeuronInfoDTO;
import java.util.List;

public class HeartBeatNeuronsResponseDTO {

    List<NeuronInfoDTO> neuronInfoDTOS;

    public List<NeuronInfoDTO> getNeuronInfoDTOS() {
        return neuronInfoDTOS;
    }

    public void setNeuronInfoDTOS(List<NeuronInfoDTO> neuronInfoDTOS) {
        this.neuronInfoDTOS = neuronInfoDTOS;
    }
}
