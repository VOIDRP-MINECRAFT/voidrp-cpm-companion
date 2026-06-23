package ru.voidrp.cpm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

public class ModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("VoidRpCpm");
    private static final Gson GSON_PRETTY = new GsonBuilder().setPrettyPrinting().create();

    @SerializedName("backend_url")
    private String backendUrl = "https://api.void-rp.ru";

    @SerializedName("game_auth_secret")
    private String gameAuthSecret = "change-me";

    @SerializedName("skin_fetch_timeout_ms")
    private int skinFetchTimeoutMs = 5000;

    private transient File configFile;

    public static ModConfig load() {
        File dir = new File("config/voidrp-cpm");
        try { dir.mkdirs(); } catch (Exception ignored) {}
        File file = new File(dir, "config.json");

        ModConfig cfg = new ModConfig();
        cfg.configFile = file;

        if (file.exists()) {
            try (Reader r = new FileReader(file)) {
                ModConfig loaded = new Gson().fromJson(r, ModConfig.class);
                if (loaded != null) {
                    cfg.backendUrl = loaded.backendUrl != null ? loaded.backendUrl : cfg.backendUrl;
                    cfg.gameAuthSecret = loaded.gameAuthSecret != null ? loaded.gameAuthSecret : cfg.gameAuthSecret;
                    cfg.skinFetchTimeoutMs = loaded.skinFetchTimeoutMs > 0 ? loaded.skinFetchTimeoutMs : cfg.skinFetchTimeoutMs;
                }
                LOGGER.info("[VoidRpCpm] Config loaded: backend={}", cfg.backendUrl);
            } catch (Exception e) {
                LOGGER.error("[VoidRpCpm] Failed to read config.json: {} — using defaults", e.getMessage());
            }
        } else {
            cfg.save();
            LOGGER.info("[VoidRpCpm] Created default config.json — set game_auth_secret before using skin fetching");
        }
        return cfg;
    }

    private void save() {
        try {
            File tmp = new File(configFile.getParentFile(), configFile.getName() + ".tmp");
            try (Writer w = new FileWriter(tmp)) {
                GSON_PRETTY.toJson(this, w);
            }
            if (configFile.exists()) configFile.delete();
            tmp.renameTo(configFile);
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Failed to save config.json: {}", e.getMessage());
        }
    }

    public String getBackendUrl() { return backendUrl; }
    public String getGameAuthSecret() { return gameAuthSecret; }
    public int getSkinFetchTimeoutMs() { return skinFetchTimeoutMs; }
}
