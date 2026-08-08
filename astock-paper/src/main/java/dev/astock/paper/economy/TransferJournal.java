package dev.astock.paper.economy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TransferJournal {
    private static final Type LIST_TYPE = new TypeToken<List<Entry>>() { }.getType();
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public TransferJournal(Path file) throws IOException {
        this.file = file;
        load();
    }

    public synchronized void put(Entry entry) throws IOException {
        entries.put(entry.transferId(), entry);
        save();
    }

    public synchronized void mark(String transferId, Phase phase) throws IOException {
        Entry old = entries.get(transferId);
        if (old == null) throw new IllegalArgumentException("journal entry not found: " + transferId);
        entries.put(transferId, new Entry(old.transferId(), old.clientRequestId(), old.playerUuid(),
                old.direction(), old.amountMinor(), phase, Instant.now().toEpochMilli()));
        save();
    }

    public synchronized void remove(String transferId) throws IOException {
        entries.remove(transferId);
        save();
    }

    public synchronized List<Entry> snapshot() {
        return List.copyOf(entries.values());
    }

    private void load() throws IOException {
        if (!Files.exists(file)) return;
        String json = Files.readString(file, StandardCharsets.UTF_8);
        if (json.isBlank()) return;
        List<Entry> loaded = gson.fromJson(json, LIST_TYPE);
        if (loaded != null) loaded.forEach(entry -> entries.put(entry.transferId(), entry));
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, gson.toJson(new ArrayList<>(entries.values())), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public enum Phase {
        SERVICE_BEGUN,
        ECONOMY_MUTATED
    }

    public record Entry(
            String transferId,
            String clientRequestId,
            UUID playerUuid,
            String direction,
            long amountMinor,
            Phase phase,
            long updatedAt
    ) {
    }
}
