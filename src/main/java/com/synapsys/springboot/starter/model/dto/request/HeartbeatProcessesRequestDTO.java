package com.synapsys.springboot.starter.model.dto.request;

import java.util.List;

public class HeartbeatProcessesRequestDTO {

    private String workerName;
    private String framework;
    private String host;
    private List<ProcessInfoDTO> processes;
    private List<ProcessLogDTO> logs;
    private Long heartbeatInterval;

    public HeartbeatProcessesRequestDTO() { }

    public HeartbeatProcessesRequestDTO(
            String workerName,
            String framework,
            String host,
            List<ProcessInfoDTO> processes,
            Long heartbeatInterval
    ) {
        this.workerName = workerName;
        this.framework = framework;
        this.host = host;
        this.processes = processes;
        this.heartbeatInterval = heartbeatInterval;
    }

    public String getWorkerName() {
        return workerName;
    }

    public void setWorkerName(String workerName) {
        this.workerName = workerName;
    }

    public String getFramework() {
        return framework;
    }

    public void setFramework(String framework) {
        this.framework = framework;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public List<ProcessInfoDTO> getProcesses() {
        return processes;
    }

    public void setProcesses(List<ProcessInfoDTO> processes) {
        this.processes = processes;
    }

    public List<ProcessLogDTO> getLogs() {
        return logs;
    }

    public void setLogs(List<ProcessLogDTO> logs) {
        this.logs = logs;
    }

    public Long getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Long heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

}
