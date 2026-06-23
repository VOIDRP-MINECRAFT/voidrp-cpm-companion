package ru.voidrp.cpm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;

public class CosmeticsConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("VoidRpCpm");
    private static final Gson GSON = new Gson();
    private static final Gson GSON_PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final File configFile;
    private final File modelsDir;
    private Map<String, String> itemSlots = new LinkedHashMap<>();

    public CosmeticsConfig() {
        File dir = new File("config/voidrp-cpm");
        try {
            dir.mkdirs();
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Failed to create config dir: {}", e.getMessage());
        }
        this.configFile = new File(dir, "cosmetics.json");
        this.modelsDir = new File(dir, "models");
        try {
            this.modelsDir.mkdirs();
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Failed to create models dir: {}", e.getMessage());
        }
        load();
    }

    /**
     * 1. Scan models/ for .bbmodel files → auto-detect names and slots
     * 2. If .bbmodel found: cosmetics.json is overwritten with ONLY scan results (cleans up stale entries)
     *    Manual extras from cosmetics.json are merged in-memory but NOT persisted back.
     * 3. If no .bbmodel: use cosmetics.json as-is (manual-only mode)
     */
    public void load() {
        try {
            Map<String, String> fromModels = BbModelParser.scanDirectory(modelsDir);

            Map<String, String> manual = new LinkedHashMap<>();
            if (configFile.exists()) {
                try (Reader r = new FileReader(configFile)) {
                    Map<String, String> loaded = GSON.fromJson(r, MAP_TYPE);
                    if (loaded != null) {
                        loaded.forEach((k, v) -> {
                            if (k != null && v != null) manual.put(k, v);
                        });
                    }
                } catch (Exception e) {
                    LOGGER.error("[VoidRpCpm] Failed to read cosmetics.json: {} — using auto-detected only", e.getMessage());
                }
            }

            if (!fromModels.isEmpty()) {
                // Auto-scan is authoritative: save only scan results (removes stale manual entries)
                saveSafe(fromModels);
                // Extra manual entries (not in any .bbmodel) are available in-memory only
                Map<String, String> merged = new LinkedHashMap<>(fromModels);
                manual.forEach((k, v) -> merged.putIfAbsent(k, v));
                itemSlots = merged;
                LOGGER.info("[VoidRpCpm] Cosmetics loaded: {} total ({} from .bbmodel, {} manual extras)",
                        itemSlots.size(), fromModels.size(), itemSlots.size() - fromModels.size());
            } else if (!manual.isEmpty()) {
                itemSlots = manual;
                LOGGER.info("[VoidRpCpm] Cosmetics loaded: {} (manual only, no .bbmodel found)", manual.size());
            } else {
                writeExample();
            }

        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Unexpected error loading cosmetics config: {}", e.getMessage(), e);
        }
    }

    private void saveSafe(Map<String, String> data) {
        try {
            File tmp = new File(configFile.getParentFile(), configFile.getName() + ".tmp");
            try (Writer w = new FileWriter(tmp)) {
                GSON_PRETTY.toJson(data, w);
            }
            if (configFile.exists()) configFile.delete();
            tmp.renameTo(configFile);
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Failed to save cosmetics.json: {}", e.getMessage());
        }
    }

    private void writeExample() {
        Map<String, String> example = new LinkedHashMap<>();
        example.put("head_tophat", "head");
        example.put("body_cape", "body");
        example.put("legs_shorts", "legs");
        example.put("feet_boots", "feet");
        example.put("wings_angel", "accessory");
        saveSafe(example);
        itemSlots = example;
        LOGGER.info("[VoidRpCpm] Created example cosmetics.json");
    }

    public String getSlot(String itemName) {
        if (itemName == null) return null;
        return itemSlots.get(itemName);
    }

    public boolean isKnown(String itemName) {
        if (itemName == null) return false;
        return itemSlots.containsKey(itemName);
    }

    public Set<String> getItemNames() {
        return Collections.unmodifiableSet(itemSlots.keySet());
    }

    public Set<String> getSlots() {
        return Collections.unmodifiableSet(new HashSet<>(itemSlots.values()));
    }
}
