package mx.edu.up.computing.ads;

/**
 * Servicios aritméticos configurables en una célula servidor del Campo de Datos ADS.
 * Cada servidor de cálculo se configura para uno y solo uno de estos servicios
 * (requisito del parcial 2, PDF oficial pp. 1–2).
 */
public enum AdsService {
    SUM,
    SUB,
    MUL,
    DIV;

    /** Código de Contenido para la solicitud (request) según el PDF oficial. */
    public String requestCode() {
        return name() + "_REQ";
    }

    /** Código de Contenido para la respuesta según el PDF oficial. */
    public String responseCode() {
        return name() + "_RES";
    }

    /** Símbolo aritmético humano, reutilizado para la GUI. */
    public String symbol() {
        return switch (this) {
            case SUM -> "+";
            case SUB -> "-";
            case MUL -> "×";
            case DIV -> "÷";
        };
    }

    public static AdsService fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Código nulo");
        }
        String upper = code.trim().toUpperCase();
        return switch (upper) {
            case "SUM", "SUM_REQ", "SUM_RES" -> SUM;
            case "SUB", "SUB_REQ", "SUB_RES" -> SUB;
            case "MUL", "MUL_REQ", "MUL_RES" -> MUL;
            case "DIV", "DIV_REQ", "DIV_RES" -> DIV;
            default -> throw new IllegalArgumentException("Servicio no reconocido: " + code);
        };
    }
}