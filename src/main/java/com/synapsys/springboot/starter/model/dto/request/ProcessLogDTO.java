package com.synapsys.springboot.starter.model.dto.request;

public class ProcessLogDTO {

    private String processName;
    private Long runId;
    private Long sequence;
    private String timestamp;
    private String source;
    private String level;
    private String message;

    public ProcessLogDTO() {}

    public ProcessLogDTO(
            String processName,
            Long runId,
            Long sequence,
            String timestamp,
            String source,
            String level,
            String message
    ) {
        this.processName = processName;
        this.runId = runId;
        this.sequence = sequence;
        this.timestamp = timestamp;
        this.source = source;
        this.level = level;
        this.message = message;
    }

    public String getProcessName() { return processName; }
    public void setProcessName(String processName) { this.processName = processName; }

    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }

    public Long getSequence() { return sequence; }
    public void setSequence(Long sequence) { this.sequence = sequence; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
