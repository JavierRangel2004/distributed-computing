package mx.edu.up.computing.common;

import java.time.Instant;

/**
 * Registro de persistencia no volátil tanto para clientes como para servidores.
 */
public class PersistenceRecord {
    private String recordId;
    private String timestamp;
    private String entityRole;     // "CLIENT" o "SERVER"
    private String entityId;       // e.g. "Client-1", "Server-1"
    private String eventType;      // "REQUEST_EMITTED", "CALCULATION_PROCESSED", "RESULT_RECEIVED"
    private String txId;
    private String originClientId;
    private String responderServerId;
    private String operation;
    private Double operandA;
    private Double operandB;
    private Double result;
    private String status;         // "SUCCESS", "ERROR_DIVISION_BY_ZERO", etc.
    private String notes;

    public PersistenceRecord() {
        this.timestamp = Instant.now().toString();
    }

    public static PersistenceRecord forServerProcessed(String serverId, NetworkMessage req, Double result, String status, String error) {
        PersistenceRecord r = new PersistenceRecord();
        r.recordId = "REC-S-" + System.currentTimeMillis();
        r.entityRole = "SERVER";
        r.entityId = serverId;
        r.eventType = "CALCULATION_PROCESSED";
        r.txId = req.getTxId();
        r.originClientId = req.getClientId();
        r.responderServerId = serverId;
        r.operation = req.getOperation() != null ? req.getOperation().name() : "UNKNOWN";
        r.operandA = req.getOperandA();
        r.operandB = req.getOperandB();
        r.result = result;
        r.status = status;
        r.notes = error != null ? error : "Operación procesada exitosamente en " + serverId;
        return r;
    }

    public static PersistenceRecord forClientRequest(String clientId, NetworkMessage req) {
        PersistenceRecord r = new PersistenceRecord();
        r.recordId = "REC-C-REQ-" + System.currentTimeMillis();
        r.entityRole = "CLIENT";
        r.entityId = clientId;
        r.eventType = "REQUEST_EMITTED";
        r.txId = req.getTxId();
        r.originClientId = clientId;
        r.operation = req.getOperation() != null ? req.getOperation().name() : "UNKNOWN";
        r.operandA = req.getOperandA();
        r.operandB = req.getOperandB();
        r.status = "PENDING";
        r.notes = "Solicitud enviada al middleware para distribución";
        return r;
    }

    public static PersistenceRecord forClientResultReceived(String clientId, NetworkMessage res) {
        PersistenceRecord r = new PersistenceRecord();
        r.recordId = "REC-C-RES-" + System.currentTimeMillis();
        r.entityRole = "CLIENT";
        r.entityId = clientId;
        r.eventType = "RESULT_RECEIVED";
        r.txId = res.getTxId();
        r.originClientId = res.getClientId();
        r.responderServerId = res.getServerId();
        r.operation = res.getOperation() != null ? res.getOperation().name() : "UNKNOWN";
        r.result = res.getResult();
        r.status = res.getStatus();
        r.notes = "Resultado recibido desde " + res.getServerId() + " a través del middleware";
        return r;
    }

    // --- Getters y Setters ---

    public String getRecordId() { return recordId; }
    public void setRecordId(String recordId) { this.recordId = recordId; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getEntityRole() { return entityRole; }
    public void setEntityRole(String entityRole) { this.entityRole = entityRole; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getTxId() { return txId; }
    public void setTxId(String txId) { this.txId = txId; }

    public String getOriginClientId() { return originClientId; }
    public void setOriginClientId(String originClientId) { this.originClientId = originClientId; }

    public String getResponderServerId() { return responderServerId; }
    public void setResponderServerId(String responderServerId) { this.responderServerId = responderServerId; }

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }

    public Double getOperandA() { return operandA; }
    public void setOperandA(Double operandA) { this.operandA = operandA; }

    public Double getOperandB() { return operandB; }
    public void setOperandB(Double operandB) { this.operandB = operandB; }

    public Double getResult() { return result; }
    public void setResult(Double result) { this.result = result; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
