package mx.edu.up.computing.ads;

import com.google.gson.Gson;

import java.time.Instant;
import java.util.UUID;

/**
 * Sobre JSON universal para los mensajes ADS.
 *
 * Campos relevantes (todos opcionales según el contexto; null permitido):
 * <ul>
 *   <li>type:           nombre del {@link AdsMessageType}.</li>
 *   <li>messageId:      UUID generado por cada mensaje en su creación.</li>
 *   <li>requestId:      UUID de la solicitud original; obligatorio solo en respuestas.</li>
 *   <li>contentCode:    redundante con type para observabilidad humana.</li>
 *   <li>originId:       identificador lógico (cliente o servidor) que origina la solicitud.</li>
 *   <li>clientId:       identificador del GUI que emite la solicitud.</li>
 *   <li>senderId:       identificador de la célula o nodo que reenvía el mensaje.</li>
 *   <li>senderRole:     rol de quien reenvía (NODE / GUI / SERVER).</li>
 *   <li>targetServerId: UUID único de instancia del servidor elegido; se fija al emitir.</li>
 *   <li>service:        servicio declarado por el servidor.</li>
 *   <li>instanceId:     UUID de instancia de la célula que envía (servidor / GUI).</li>
 *   <li>host / port:    dirección de la célula para el ADVERTISE inicial.</li>
 *   <li>operandA, operandB, result, status, error.</li>
 *   <li>createdAt / expiresAt: timestamps en milisegundos epoch.</li>
 *   <li>hopCount:       contador de saltos (solo observabilidad).</li>
 * </ul>
 *
 * La clase es un POJO mutable con getters/setters porque Gson la serializa por reflexión
 * directamente; ningún acoplamiento con la estructura histórica de
 * {@code mx.edu.up.computing.common.NetworkMessage}.
 */
public class AdsMessage {

    private static final Gson GSON = new com.google.gson.GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private String type;
    private String messageId;
    private String requestId;
    private String contentCode;
    private String originId;
    private String clientId;
    private String senderId;
    private String senderRole;
    private String targetServerId;
    private String service;
    private String instanceId;
    private String nodeId;
    private String host;
    private Integer port;
    private Double operandA;
    private Double operandB;
    private Double result;
    private String status;
    private String error;
    private Long createdAt;
    private Long expiresAt;
    private Integer hopCount;

    public AdsMessage() {
    }

    public static AdsMessage create(AdsMessageType type) {
        AdsMessage m = new AdsMessage();
        long now = Instant.now().toEpochMilli();
        m.type = type.name();
        m.messageId = UUID.randomUUID().toString();
        m.createdAt = now;
        m.expiresAt = now + AdsConstants.MESSAGE_TTL_MS;
        m.hopCount = 0;
        return m;
    }

    public AdsMessageType getTypeEnum() {
        return type == null ? null : AdsMessageType.fromString(type);
    }

    public AdsRole getSenderRoleEnum() {
        return senderRole == null ? null : AdsRole.fromString(senderRole);
    }

    public AdsService getServiceEnum() {
        return service == null ? null : AdsService.fromCode(service);
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().toEpochMilli() > expiresAt;
    }

    public void bumpHop() {
        if (hopCount == null) {
            hopCount = 0;
        }
        hopCount = hopCount + 1;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static AdsMessage fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        return GSON.fromJson(json, AdsMessage.class);
    }

    // ----- Getters / Setters -----

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getContentCode() { return contentCode; }
    public void setContentCode(String contentCode) { this.contentCode = contentCode; }

    public String getOriginId() { return originId; }
    public void setOriginId(String originId) { this.originId = originId; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public String getSenderRole() { return senderRole; }
    public void setSenderRole(String senderRole) { this.senderRole = senderRole; }

    public String getTargetServerId() { return targetServerId; }
    public void setTargetServerId(String targetServerId) { this.targetServerId = targetServerId; }

    public String getService() { return service; }
    public void setService(String service) { this.service = service; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

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

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }

    public Integer getHopCount() { return hopCount; }
    public void setHopCount(Integer hopCount) { this.hopCount = hopCount; }
}