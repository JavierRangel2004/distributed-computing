package mx.edu.up.computing.ads;

/**
 * Dirección canónica observada de una conexión TCP entre dos nodos.
 * El nodo con menor ID entre (self, peer) inicia el dial: por tanto
 * prefiere {@link #OUTBOUND} (su dial) y el nodo mayor prefiere
 * {@link #INBOUND} (acepta el dial del menor).
 */
public enum AdsDirection {
    INBOUND,
    OUTBOUND
}