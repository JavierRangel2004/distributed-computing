package mx.edu.up.computing.ads;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Logger ligero basado en {@link System#out} con marcas de rol, hora y etiqueta.
 * No silencia errores; cada hilo lo invoca de forma segura porque {@code print}
 * está sincronizado por la consola del sistema.
 */
public final class AdsLogger {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final String prefix;

    public AdsLogger(String prefix) {
        this.prefix = prefix;
    }

    public void info(String tag, String text) {
        System.out.println(format("INFO ", tag, text));
    }

    public void warn(String tag, String text) {
        System.out.println(format("WARN ", tag, text));
    }

    public void error(String tag, String text) {
        System.out.println(format("ERROR", tag, text));
    }

    public void error(String tag, String text, Throwable t) {
        System.out.println(format("ERROR", tag, text));
        if (t != null) {
            t.printStackTrace(System.out);
        }
    }

    public void event(String tag, String text) {
        System.out.println(format("EVENT", tag, text));
    }

    private String format(String level, String tag, String text) {
        String time = LocalTime.now().format(TIME_FMT);
        return String.format("[%s] [%s] [%s] [%s] %s", time, prefix, level, tag, text);
    }
}