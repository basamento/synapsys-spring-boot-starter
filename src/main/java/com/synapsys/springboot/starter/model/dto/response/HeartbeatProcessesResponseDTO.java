package com.synapsys.springboot.starter.model.dto.response;

import com.synapsys.springboot.starter.model.dto.request.ProcessInfoDTO;
import java.util.List;

public class HeartbeatProcessesResponseDTO {

    List<ProcessInfoDTO> processes;

    public List<ProcessInfoDTO> getProcesses() {
        return processes;
    }

    public void setProcesses(List<ProcessInfoDTO> processes) {
        this.processes = processes;
    }
}