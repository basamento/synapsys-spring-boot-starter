package com.synapsys.springboot.starter.model.dto.request;

import java.util.List;

public class HeartbeatProcessesRequestDTO {

    private String workerName;
    private List<ProcessInfoDTO> processes;
    private Long heartbeatInterval;

    public HeartbeatProcessesRequestDTO() { }

    public HeartbeatProcessesRequestDTO(String workerName, List<ProcessInfoDTO> processes, Long heartbeatInterval) {
        this.workerName = workerName;
        this.processes = processes;
        this.heartbeatInterval = heartbeatInterval;
    }

    public String getWorkerName() {
        return workerName;
    }

    public void setWorkerName(String workerName) {
        this.workerName = workerName;
    }

    public List<ProcessInfoDTO> getProcesses() {
        return processes;
    }

    public void setProcesses(List<ProcessInfoDTO> processes) {
        this.processes = processes;
    }

    public Long getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Long heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

}
