package mx.edu.up.computing.common;

import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Servicio de almacenamiento no volátil en disco.
 * Gestiona archivos JSON estructurados con transacciones históricas.
 */
public class PersistenceService {
    private static final String BASE_DIR = "data";
    private final String roleFolder; // "servers" o "clients"
    private final String entityId;
    private final File dataFile;
    private final Object lock = new Object();

    public PersistenceService(Role role, String entityId) {
        this.roleFolder = (role == Role.SERVER) ? "servers" : "clients";
        this.entityId = entityId;

        File folder = new File(BASE_DIR, this.roleFolder);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        this.dataFile = new File(folder, entityId + "_history.json");
    }

    /**
     * Guarda un registro de forma atómica y sincronizada en el archivo JSON.
     */
    public void appendRecord(PersistenceRecord record) {
        synchronized (lock) {
            List<PersistenceRecord> records = loadAll();
            records.add(record);
            saveAll(records);
        }
    }

    /**
     * Carga todos los registros almacenados en el archivo.
     */
    public List<PersistenceRecord> loadAll() {
        synchronized (lock) {
            if (!dataFile.exists() || dataFile.length() == 0) {
                return new ArrayList<>();
            }
            try (FileReader reader = new FileReader(dataFile, StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<ArrayList<PersistenceRecord>>() {}.getType();
                List<PersistenceRecord> list = JsonCodec.getPrettyGson().fromJson(reader, listType);
                return list != null ? list : new ArrayList<>();
            } catch (Exception e) {
                System.err.println("[PERSISTENCE] Error leyendo " + dataFile.getAbsolutePath() + ": " + e.getMessage());
                return new ArrayList<>();
            }
        }
    }

    /**
     * Sobrescribe el archivo de forma segura con la lista completa de registros.
     */
    private void saveAll(List<PersistenceRecord> records) {
        File tempFile = new File(dataFile.getParentFile(), dataFile.getName() + ".tmp");
        try {
            try (FileWriter writer = new FileWriter(tempFile, StandardCharsets.UTF_8)) {
                JsonCodec.getPrettyGson().toJson(records, writer);
                writer.flush();
            }
            java.nio.file.Path tempPath = tempFile.toPath();
            java.nio.file.Path targetPath = dataFile.toPath();
            try {
                java.nio.file.Files.move(tempPath, targetPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException e) {
                java.nio.file.Files.move(tempPath, targetPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[PERSISTENCE] Error escribiendo archivo: " + e.getMessage());
        }
    }

    public File getDataFile() {
        return dataFile;
    }

    public String getEntityId() {
        return entityId;
    }
}
