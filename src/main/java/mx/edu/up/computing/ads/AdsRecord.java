package mx.edu.up.computing.ads;

import java.util.UUID;

/**
 * Registro no volátil para el campo ADS. Se persiste bajo {@link AdsConstants#DATA_ROOT}
 * en archivos por instancia, separados de los historiales del primer parcial.
 */
public class AdsRecord {

    private String recordId;
    private String timestamp;
    private String role;
    private String entityId;
    private String eventType;
    private String messageId;
    private String requestId;
    private String targetServerId;
    private String service;
    private Double operandA;
    private Double operandB;
    private Double result;
    private String status;
    private String error;
    private String notes;

    public AdsRecord() {
        this.timestamp = java.time.Instant.now().toString();
        this.recordId = "ADS-" + UUID.randomUUID().toString().substring(0, 12);
    }

    public static AdsRecord fromMessage(String role, String entityId, String eventType, AdsMessage msg) {
        AdsRecord r = new AdsRecord();
        r.role = role;
        r.entityId = entityId;
        r.eventType = eventType;
        if (msg != null) {
            r.messageId = msg.getMessageId();
            r.requestId = msg.getRequestId();
            r.targetServerId = msg.getTargetServerId();
            r.service = msg.getService();
            r.operandA = msg.getOperandA();
            r.operandB = msg.getOperandB();
            r.result = msg.getResult();
            r.status = msg.getStatus();
            r.error = msg.getError();
        }
        return r;
    }

    public String getRecordId() { return recordId; }
    public void setRecordId(String recordId) { this.recordId = recordId; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getTargetServerId() { return targetServerId; }
    public void setTargetServerId(String targetServerId) { this.targetServerId = targetServerId; }

    public String getService() { return service; }
    public void setService(String service) { this.service = service; }

    public Double getOperandA() { return operandA; }
    public void setOperandA(Double operandA) { this.operandA = operandA; }

    public Double getOperandB() { return operandB; }
    public void setOperandB(Double operandB) { this.operandB = operandB; }

    public Double getResult() { return result; }
    public void setResult(Double result) { this.result = result; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}