package mx.edu.up.computing.ads;

/**
 * Utilidad para mapear entre {@link AdsMessageType} y códigos de contenido (*_REQ/*_RES)
 * exigidos por el PDF del parcial 2 (pp. 2–3).
 */
public final class AdsContentCodes {

    private AdsContentCodes() {
    }

    public static AdsMessageType requestTypeFor(AdsService service) {
        return switch (service) {
            case SUM -> AdsMessageType.SUM_REQ;
            case SUB -> AdsMessageType.SUB_REQ;
            case MUL -> AdsMessageType.MUL_REQ;
            case DIV -> AdsMessageType.DIV_REQ;
        };
    }

    public static AdsMessageType responseTypeFor(AdsService service) {
        return switch (service) {
            case SUM -> AdsMessageType.SUM_RES;
            case SUB -> AdsMessageType.SUB_RES;
            case MUL -> AdsMessageType.MUL_RES;
            case DIV -> AdsMessageType.DIV_RES;
        };
    }

    public static AdsService serviceOfRequest(AdsMessageType requestType) {
        return switch (requestType) {
            case SUM_REQ -> AdsService.SUM;
            case SUB_REQ -> AdsService.SUB;
            case MUL_REQ -> AdsService.MUL;
            case DIV_REQ -> AdsService.DIV;
            default -> throw new IllegalArgumentException("No es solicitud: " + requestType);
        };
    }

    public static AdsService serviceOfResponse(AdsMessageType responseType) {
        return switch (responseType) {
            case SUM_RES -> AdsService.SUM;
            case SUB_RES -> AdsService.SUB;
            case MUL_RES -> AdsService.MUL;
            case DIV_RES -> AdsService.DIV;
            default -> throw new IllegalArgumentException("No es respuesta: " + responseType);
        };
    }

    public static boolean isRequest(AdsMessageType type) {
        return type == AdsMessageType.SUM_REQ
                || type == AdsMessageType.SUB_REQ
                || type == AdsMessageType.MUL_REQ
                || type == AdsMessageType.DIV_REQ;
    }

    public static boolean isResponse(AdsMessageType type) {
        return type == AdsMessageType.SUM_RES
                || type == AdsMessageType.SUB_RES
                || type == AdsMessageType.MUL_RES
                || type == AdsMessageType.DIV_RES;
    }
}