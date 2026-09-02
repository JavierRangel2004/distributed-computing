package mx.edu.up.computing.server;

import mx.edu.up.computing.common.OperationType;

/**
 * Motor de cálculo aritmético de la Capa de Lógica de Negocio.
 * Resuelve suma, resta, multiplicación y división con validación de división entre cero.
 */
public class CalculatorEngine {

    public static class CalculationResult {
        private final Double value;
        private final String status;
        private final String errorMessage;

        public CalculationResult(Double value) {
            this.value = value;
            this.status = "SUCCESS";
            this.errorMessage = null;
        }

        public CalculationResult(String status, String errorMessage) {
            this.value = null;
            this.status = status;
            this.errorMessage = errorMessage;
        }

        public Double getValue() { return value; }
        public String getStatus() { return status; }
        public String getErrorMessage() { return errorMessage; }
        public boolean isSuccess() { return "SUCCESS".equals(status); }
    }

    /**
     * Ejecuta la operación aritmética solicitada.
     */
    public CalculationResult calculate(OperationType operation, double a, double b) {
        if (operation == null) {
            return new CalculationResult("INVALID_OPERATION", "Operación no especificada o nula");
        }

        switch (operation) {
            case ADD:
                return new CalculationResult(a + b);

            case SUBTRACT:
                return new CalculationResult(a - b);

            case MULTIPLY:
                return new CalculationResult(a * b);

            case DIVIDE:
                if (Math.abs(b) < 1e-12) {
                    return new CalculationResult("ERROR_DIVISION_BY_ZERO", "Error aritmético: División entre cero no permitida");
                }
                return new CalculationResult(a / b);

            default:
                return new CalculationResult("UNSUPPORTED_OPERATION", "Operación no implementada: " + operation);
        }
    }
}
