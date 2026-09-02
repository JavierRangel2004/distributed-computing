package mx.edu.up.computing.common;

/**
 * Operaciones aritméticas básicas soportadas por el sistema distribuido.
 */
public enum OperationType {
    ADD("+", "Suma"),
    SUBTRACT("-", "Resta"),
    MULTIPLY("×", "Multiplicación"),
    DIVIDE("÷", "División");

    private final String symbol;
    private final String displayName;

    OperationType(String symbol, String displayName) {
        this.symbol = symbol;
        this.displayName = displayName;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static OperationType fromSymbolOrName(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Operación vacía");
        }
        String clean = text.trim().toUpperCase();
        for (OperationType op : values()) {
            if (op.name().equals(clean) ||
                op.symbol.equals(text.trim()) ||
                op.symbol.equals("*") && op == MULTIPLY ||
                op.symbol.equals("/") && op == DIVIDE) {
                return op;
            }
        }
        if (clean.equals("*")) return MULTIPLY;
        if (clean.equals("/")) return DIVIDE;
        throw new IllegalArgumentException("Operación no reconocida: " + text);
    }
}
