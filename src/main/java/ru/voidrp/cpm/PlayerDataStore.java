package ru.voidrp.cpm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;

public class PlayerDataStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("VoidRpCpm");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Entry>>() {}.getType();

    public static class Entry {
        public Set<String> owned = new HashSet<>();
        public Map<String, String> slots = new HashMap<>();
    }

    private final File dataFile;
    private Map<String, Entry> data = new HashMap<>();

    public PlayerDataStore() {
        File dir = new File("config/voidrp-cpm");
        try {
            dir.mkdirs();
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Failed to create config dir: {}", e.getMessage());
        }
        this.dataFile = new File(dir, "players.json");
        load();
    }

    private synchronized void load() {
        if (!dataFile.exists()) return;
        try (Reader r = new FileReader(dataFile)) {
            Map<String, Entry> loaded = GSON.fromJson(r, MAP_TYPE);
            if (loaded != null) {
                // Sanitize nulls from deserialization
                loaded.forEach((uuid, entry) -> {
                    if (entry.owned == null) entry.owned = new HashSet<>();
                    if (entry.slots == null) entry.slots = new HashMap<>();
                });
                data = loaded;
            }
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Failed to load players.json: {} — starting with empty data", e.getMessage());
            data = new HashMap<>();
        }
    }

    private synchronized void save() {
        try {
            File tmp = new File(dataFile.getParentFile(), dataFile.getName() + ".tmp");
            try (Writer w = new FileWriter(tmp)) {
                GSON.toJson(data, w);
            }
            // Atomic replace
            if (dataFile.exists()) dataFile.delete();
            tmp.renameTo(dataFile);
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Failed to save players.json: {}", e.getMessage());
        }
    }

    private Entry getOrCreate(String uuid) {
        return data.computeIfAbsent(uuid, k -> new Entry());
    }

    public synchronized boolean ownsCosmetic(String uuid, String item) {
        try {
            Entry e = data.get(uuid);
            return e != null && e.owned.contains(item);
        } catch (Exception e) {
            LOGGER.warn("[VoidRpCpm] ownsCosmetic error for {}: {}", uuid, e.getMessage());
            return false;
        }
    }

    public synchronized Set<String> getOwned(String uuid) {
        try {
            Entry e = data.get(uuid);
            return e == null ? Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(e.owned));
        } catch (Exception e) {
            LOGGER.warn("[VoidRpCpm] getOwned error for {}: {}", uuid, e.getMessage());
            return Collections.emptySet();
        }
    }

    public synchronized Map<String, String> getSlots(String uuid) {
        try {
            Entry e = data.get(uuid);
            return e == null ? Collections.emptyMap() : Collections.unmodifiableMap(new HashMap<>(e.slots));
        } catch (Exception e) {
            LOGGER.warn("[VoidRpCpm] getSlots error for {}: {}", uuid, e.getMessage());
            return Collections.emptyMap();
        }
    }

    public synchronized String getSlotItem(String uuid, String slot) {
        try {
            Entry e = data.get(uuid);
            return e == null ? null : e.slots.get(slot);
        } catch (Exception e) {
            LOGGER.warn("[VoidRpCpm] getSlotItem error for {}: {}", uuid, e.getMessage());
            return null;
        }
    }

    public synchronized void grant(String uuid, String item) {
        try {
            getOrCreate(uuid).owned.add(item);
            save();
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] grant error for {}: {}", uuid, e.getMessage());
        }
    }

    public synchronized void revoke(String uuid, String item) {
        try {
            Entry e = data.get(uuid);
            if (e == null) return;
            e.owned.remove(item);
            e.slots.values().removeIf(v -> v.equals(item));
            save();
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] revoke error for {}: {}", uuid, e.getMessage());
        }
    }

    public synchronized void equip(String uuid, String slot, String item) {
        try {
            getOrCreate(uuid).slots.put(slot, item);
            save();
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] equip error for {}: {}", uuid, e.getMessage());
        }
    }

    public synchronized void unequip(String uuid, String slot) {
        try {
            Entry e = data.get(uuid);
            if (e != null) {
                e.slots.remove(slot);
                save();
            }
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] unequip error for {}: {}", uuid, e.getMessage());
        }
    }
}
