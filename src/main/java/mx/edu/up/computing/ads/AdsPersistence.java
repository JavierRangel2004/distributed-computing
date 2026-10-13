package mx.edu.up.computing.ads;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistencia no volátil para entidades ADS (nodos, GUIs y servidores).
 *
 * Almacena bajo el directorio raíz configurable (por defecto
 * {@link AdsConstants#DATA_ROOT}) en subcarpetas por rol:
 * <ul>
 *   <li>{@code nodes/&lt;id&gt;_history.json}</li>
 *   <li>{@code guis/&lt;id&gt;_history.json}</li>
 *   <li>{@code servers/&lt;id&gt;_history.json}</li>
 * </ul>
 *
 * Las escrituras son atómicas (escritura en archivo .tmp + renombrado); las
 * lecturas toleran archivos inexistentes. No toca historiales del parcial 1.
 *
 * Las pruebas pueden pasar un directorio temporal para evitar compartir
 * archivos durante una suite completa.
 */
public class AdsPersistence {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private final String roleFolder;
    private final String entityId;
    private final File dataFile;
    private final Object lock = new Object();

    public AdsPersistence(String roleFolder, String entityId) {
        this(resolveRoot(), roleFolder, entityId);
    }

    public AdsPersistence(String rootDir, String roleFolder, String entityId) {
        if (rootDir == null || rootDir.isBlank()) {
            throw new IllegalArgumentException("rootDir vacío");
        }
        if (roleFolder == null || roleFolder.isBlank()) {
            throw new IllegalArgumentException("roleFolder vacío");
        }
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("entityId vacío");
        }
        this.roleFolder = roleFolder;
        this.entityId = entityId;
        File root = new File(rootDir);
        File folder = new File(root, roleFolder);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IllegalStateException("No se pudo crear " + folder.getAbsolutePath());
        }
        String safeId = entityId.replaceAll("[^A-Za-z0-9_.\\-]", "_");
        this.dataFile = new File(folder, safeId + "_history.json");
    }

    private static String resolveRoot() {
        String prop = System.getProperty("ads.persistence.root");
        return (prop == null || prop.isBlank()) ? AdsConstants.DATA_ROOT : prop;
    }

    public void appendRecord(AdsRecord record) {
        if (record == null) {
            return;
        }
        synchronized (lock) {
            List<AdsRecord> all = loadAll();
            all.add(record);
            save(all);
        }
    }

    public List<AdsRecord> loadAll() {
        synchronized (lock) {
            if (!dataFile.exists() || dataFile.length() == 0) {
                return new ArrayList<>();
            }
            try (FileReader reader = new FileReader(dataFile, StandardCharsets.UTF_8)) {
                Type t = new TypeToken<ArrayList<AdsRecord>>() {}.getType();
                List<AdsRecord> list = GSON.fromJson(reader, t);
                return list != null ? list : new ArrayList<>();
            } catch (Exception e) {
                System.err.println("[AdsPersistence] Error leyendo " + dataFile + ": " + e.getMessage());
                return new ArrayList<>();
            }
        }
    }

    private void save(List<AdsRecord> records) {
        File tmp = new File(dataFile.getParentFile(), dataFile.getName() + ".tmp");
        try {
            // try-with-resources cierra el FileWriter ANTES del move (algunos
            // sistemas operativos rechazan rename sobre handle abierto).
            try (FileWriter writer = new FileWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(records, writer);
                writer.flush();
            }
            try {
                Files.move(tmp.toPath(), dataFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicEx) {
                // Fallback no-atómico exacto: REPLACE_EXISTING sin ATOMIC_MOVE.
                Files.move(tmp.toPath(), dataFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[AdsPersistence] Error escribiendo " + dataFile + ": " + e.getMessage());
        }
    }

    public File getDataFile() {
        return dataFile;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getRoleFolder() {
        return roleFolder;
    }

    public List<AdsRecord> snapshot() {
        return Collections.unmodifiableList(loadAll());
    }
}