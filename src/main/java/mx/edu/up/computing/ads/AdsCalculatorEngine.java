package mx.edu.up.computing.ads;

/**
 * Motor de cálculo aritmético para una célula servidor ADS.
 *
 * Reglas duras (invariantes del sistema):
 * <ul>
 *   <li>La división entre cero se detecta por igualdad exacta con 0.0
 *       (no se aplica epsilon: cualquier número distinto de 0 produce cociente).</li>
 *   <li>Los operandos y resultados no finitos (NaN/Infinity) se rechazan
 *       para no contaminar el JSON.</li>
 *   <li>La operación debe corresponder al servicio configurado de la célula;
 *       un servicio SUM jamás procesa una DIV_REQ.</li>
 * </ul>
 */
public final class AdsCalculatorEngine {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_DIVISION_BY_ZERO = "ERROR_DIVISION_BY_ZERO";
    public static final String STATUS_NON_FINITE = "ERROR_NON_FINITE";
    public static final String STATUS_SERVICE_MISMATCH = "ERROR_SERVICE_MISMATCH";
    public static final String STATUS_INVALID_INPUT = "ERROR_INVALID_INPUT";

    private final AdsService service;

    public AdsCalculatorEngine(AdsService service) {
        this.service = service;
    }

    public AdsService service() {
        return service;
    }

    public AdsCalculationResult compute(AdsMessage request) {
        if (request == null) {
            return new AdsCalculationResult(null, STATUS_INVALID_INPUT, "Mensaje nulo");
        }
        AdsMessageType type = request.getTypeEnum();
        if (type == null || !AdsContentCodes.isRequest(type)) {
            return new AdsCalculationResult(null, STATUS_INVALID_INPUT,
                    "Tipo de mensaje no es *_REQ: " + type);
        }
        AdsService requested = AdsContentCodes.serviceOfRequest(type);
        if (requested != service) {
            return new AdsCalculationResult(null, STATUS_SERVICE_MISMATCH,
                    "Servicio configurado=" + service + " solicitado=" + requested);
        }
        Double a = request.getOperandA();
        Double b = request.getOperandB();
        if (a == null || b == null) {
            return new AdsCalculationResult(null, STATUS_INVALID_INPUT,
                    "Operandos nulos");
        }
        if (!Double.isFinite(a) || !Double.isFinite(b)) {
            return new AdsCalculationResult(null, STATUS_NON_FINITE,
                    "Operandos no finitos: " + a + ", " + b);
        }
        double r;
        switch (service) {
            case SUM -> r = a + b;
            case SUB -> r = a - b;
            case MUL -> r = a * b;
            case DIV -> {
                if (b == 0.0) {
                    return new AdsCalculationResult(null, STATUS_DIVISION_BY_ZERO,
                            "División entre cero exacta");
                }
                r = a / b;
            }
            default -> {
                return new AdsCalculationResult(null, STATUS_SERVICE_MISMATCH,
                        "Servicio no soportado: " + service);
            }
        }
        if (!Double.isFinite(r)) {
            return new AdsCalculationResult(null, STATUS_NON_FINITE,
                    "Resultado no finito: " + r);
        }
        return new AdsCalculationResult(r, STATUS_SUCCESS, null);
    }

    /**
     * Resultado inmutable de un cálculo.
     */
    public static final class AdsCalculationResult {
        private final Double value;
        private final String status;
        private final String errorMessage;

        public AdsCalculationResult(Double value, String status, String errorMessage) {
            this.value = value;
            this.status = status;
            this.errorMessage = errorMessage;
        }

        public Double value() { return value; }
        public String status() { return status; }
        public String errorMessage() { return errorMessage; }
        public boolean isSuccess() { return STATUS_SUCCESS.equals(status); }
    }
}