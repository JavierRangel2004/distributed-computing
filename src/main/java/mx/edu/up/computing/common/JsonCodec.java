package mx.edu.up.computing.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Utilería de serialización y deserialización JSON para comunicación por sockets y persistencia.
 */
public class JsonCodec {
    private static final Gson COMPACT_GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private static final Gson PRETTY_GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    /**
     * Serializa un objeto a JSON en una sola línea (ideal para sockets delimitados por '\n').
     */
    public static String toJsonLine(Object obj) {
        if (obj == null) return "";
        return COMPACT_GSON.toJson(obj).replace("\r", "").replace("\n", " ");
    }

    /**
     * Serializa un objeto a JSON con formato legible (indentado).
     */
    public static String toPrettyJson(Object obj) {
        if (obj == null) return "{}";
        return PRETTY_GSON.toJson(obj);
    }

    /**
     * Deserializa un mensaje desde JSON.
     */
    public static NetworkMessage fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        return COMPACT_GSON.fromJson(json.trim(), NetworkMessage.class);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        return COMPACT_GSON.fromJson(json.trim(), clazz);
    }

    public static Gson getPrettyGson() {
        return PRETTY_GSON;
    }

    public static Gson getCompactGson() {
        return COMPACT_GSON;
    }
}
