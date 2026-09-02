package mx.edu.up.computing.common;

import java.time.Instant;
import java.util.UUID;

/**
 * Envoltorio canónico para los mensajes intercambiados a través de sockets TCP.
 * Se serializa como una cadena JSON de una sola línea delimitada por salto de línea ('\n').
 */
public class NetworkMessage {
    private MessageType type;
    private String txId;
    private Role role;
    private String senderId;
    private String clientId;
    private String serverId;
    private OperationType operation;
    private Double operandA;
    private Double operandB;
    private Double result;
    private String status;        // "SUCCESS", "ERROR_DIVISION_BY_ZERO", "INVALID_REQUEST", etc.
    private String errorMessage;
    private String timestamp;

    public NetworkMessage() {
        this.timestamp = Instant.now().toString();
    }

    // --- Métodos de fábrica para construcción rápida de mensajes ---

    public static NetworkMessage createRegister(Role role, String senderId) {
        NetworkMessage msg = new NetworkMessage();
        msg.setType(MessageType.REGISTER);
        msg.setRole(role);
        msg.setSenderId(senderId);
        if (role == Role.CLIENT) {
            msg.setClientId(senderId);
        } else {
            msg.setServerId(senderId);
        }
        return msg;
    }

    public static NetworkMessage createRegisterAck(String senderId, String message) {
        NetworkMessage msg = new NetworkMessage();
        msg.setType(MessageType.REGISTER_ACK);
        msg.setSenderId(senderId);
        msg.setStatus("SUCCESS");
        msg.setErrorMessage(message);
        return msg;
    }

    public static NetworkMessage createCalculateRequest(String clientId, OperationType op, double a, double b) {
        NetworkMessage msg = new NetworkMessage();
        msg.setType(MessageType.CALCULATE_REQ);
        msg.setTxId("TX-" + UUID.randomUUID().toString().substring(0, 8));
        msg.setSenderId(clientId);
        msg.setClientId(clientId);
        msg.setOperation(op);
        msg.setOperandA(a);
        msg.setOperandB(b);
        return msg;
    }

    public static NetworkMessage createCalculateResponse(String txId, String clientId, String serverId,
                                                          OperationType op, Double result,
                                                          String status, String errorMessage) {
        NetworkMessage msg = new NetworkMessage();
        msg.setType(MessageType.CALCULATE_RES);
        msg.setTxId(txId);
        msg.setSenderId(serverId);
        msg.setClientId(clientId);
        msg.setServerId(serverId);
        msg.setOperation(op);
        msg.setResult(result);
        msg.setStatus(status);
        msg.setErrorMessage(errorMessage);
        return msg;
    }

    public static NetworkMessage createError(String senderId, String errorMessage) {
        NetworkMessage msg = new NetworkMessage();
        msg.setType(MessageType.ERROR);
        msg.setSenderId(senderId);
        msg.setStatus("ERROR");
        msg.setErrorMessage(errorMessage);
        return msg;
    }

    // --- Getters y Setters ---

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getTxId() {
        return txId;
    }

    public void setTxId(String txId) {
        this.txId = txId;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public OperationType getOperation() {
        return operation;
    }

    public void setOperation(OperationType operation) {
        this.operation = operation;
    }

    public Double getOperandA() {
        return operandA;
    }

    public void setOperandA(Double operandA) {
        this.operandA = operandA;
    }

    public Double getOperandB() {
        return operandB;
    }

    public void setOperandB(Double operandB) {
        this.operandB = operandB;
    }

    public Double getResult() {
        return result;
    }

    public void setResult(Double result) {
        this.result = result;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "NetworkMessage{" +
                "type=" + type +
                ", txId='" + txId + '\'' +
                ", senderId='" + senderId + '\'' +
                ", clientId='" + clientId + '\'' +
                ", serverId='" + serverId + '\'' +
                ", operation=" + operation +
                ", a=" + operandA +
                ", b=" + operandB +
                ", result=" + result +
                ", status='" + status + '\'' +
                '}';
    }
}
