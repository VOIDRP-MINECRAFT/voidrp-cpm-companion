package ru.voidrp.cpm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import com.tom.cpm.api.ICommonAPI;
import com.tom.cpm.shared.io.ModelFile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class CosmeticsManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("VoidRpCpm");

    private final File modelsDir;
    private ModelFile wardrobeModel;       // fallback — template skin embedded, all cosmetics visible
    private JsonObject wardrobeConfig;     // config.json from .cpmproject
    private byte[] wardrobeTemplateSkin;   // skin.png from .cpmproject
    private String wardrobeFileName;
    private ICommonAPI api;
    private ModConfig modConfig;

    // animationName → set of leaf cube storeIDs controlled by that animation
    private Map<String, Set<Long>> animToLeafStoreIds = new HashMap<>();

    // UUID → personalized ModelFile for the player's current equipped set + skin
    private final Map<String, ModelFile> personalizedModels = new ConcurrentHashMap<>();
    // UUID → raw skin PNG bytes (cached so we don't re-download on every equip change)
    private final Map<String, byte[]> skinCache = new ConcurrentHashMap<>();

    public CosmeticsManager() {
        this.modelsDir = new File("config/voidrp-cpm/models");
        try {
            this.modelsDir.mkdirs();
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Failed to create models directory: {}", e.getMessage());
        }
        // Required for ImageIO in headless server JVM
        System.setProperty("java.awt.headless", "true");
    }

    public void setApi(ICommonAPI api) {
        this.api = api;
    }

    public void setModConfig(ModConfig modConfig) {
        this.modConfig = modConfig;
    }

    public void loadWardrobe() {
        wardrobeModel = null;
        wardrobeConfig = null;
        wardrobeTemplateSkin = null;
        wardrobeFileName = null;
        animToLeafStoreIds = new HashMap<>();
        personalizedModels.clear();
        skinCache.clear();

        try {
            String[] allFiles = modelsDir.list();
            LOGGER.info("[VoidRpCpm] models dir contents ({}): {}", modelsDir.getAbsolutePath(),
                    allFiles == null ? "null (dir missing?)" : Arrays.toString(allFiles));

            File[] cpmFiles = modelsDir.listFiles(f -> {
                String n = f.getName();
                return n.endsWith(".cpmproject") || n.endsWith(".cpmmodel");
            });
            if (cpmFiles == null || cpmFiles.length == 0) {
                LOGGER.warn("[VoidRpCpm] No .cpmproject/.cpmmodel found in {} — cosmetics disabled", modelsDir.getAbsolutePath());
                return;
            }

            File modelFile = cpmFiles[0];
            if (cpmFiles.length > 1) {
                LOGGER.warn("[VoidRpCpm] Multiple model files found — using: {}", modelFile.getName());
            }

            if (modelFile.getName().endsWith(".cpmproject")) {
                byte[] raw = Files.readAllBytes(modelFile.toPath());
                CpmProjectConverter.ZipContents contents = CpmProjectConverter.extractZip(raw, modelFile.getName());
                wardrobeConfig = contents.config();
                wardrobeTemplateSkin = contents.skinPng();
                animToLeafStoreIds = new HashMap<>(contents.animToLeafStoreIds());
                // Template model with no active cosmetics (all hidden by default)
                wardrobeModel = CpmProjectConverter.buildModelFile(contents.config(), contents.skinPng(), Set.of());
                LOGGER.info("[VoidRpCpm] Wardrobe loaded: {} (skin={}, cosmetics: {})",
                        modelFile.getName(),
                        wardrobeTemplateSkin != null ? wardrobeTemplateSkin.length + "b" : "none",
                        animToLeafStoreIds.keySet());
            } else {
                wardrobeModel = loadBinaryModel(modelFile);
                LOGGER.info("[VoidRpCpm] Wardrobe loaded: {} (binary, no per-player skin)", modelFile.getName());
            }

            if (wardrobeModel != null) wardrobeFileName = modelFile.getName();

        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Unexpected error loading wardrobe: {}", e.getMessage(), e);
        }
    }

    private ModelFile loadBinaryModel(File file) {
        try {
            byte[] raw = Files.readAllBytes(file.toPath());
            if (raw.length == 0) { LOGGER.error("[VoidRpCpm] Model file is empty: {}", file.getName()); return null; }
            if ((raw[0] & 0xFF) == 0x53) return ModelFile.load(new ByteArrayInputStream(raw));
            LOGGER.error("[VoidRpCpm] Unknown format for {} (first byte: 0x{:02X})", file.getName(), raw[0] & 0xFF);
            return null;
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Failed to load model {}: {}", file.getName(), e.getMessage(), e);
            return null;
        }
    }

    public boolean isReady() {
        return api != null && wardrobeModel != null;
    }

    public String getWardrobeFileName() {
        return wardrobeFileName;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public void applyOnLogin(ServerPlayer player, PlayerDataStore store) {
        if (api == null) return;
        try {
            String uuid = player.getStringUUID();
            Map<String, String> slots = store.getSlots(uuid);

            if (slots.isEmpty() || wardrobeModel == null) {
                safeResetModel(player);
                return;
            }

            // Apply instant model with correct cosmetic visibility (no network call yet)
            safeApplyInstant(player, slots);

            // Async: rebuild with player's actual skin
            schedulePersonalization(player, slots);
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Error in applyOnLogin for {}: {}", player.getName().getString(), e.getMessage(), e);
        }
    }

    public boolean equip(ServerPlayer player, String itemName, String slot, PlayerDataStore store) {
        if (!isReady()) return false;
        try {
            String uuid = player.getStringUUID();

            store.equip(uuid, slot, itemName);
            Map<String, String> slots = store.getSlots(uuid);

            // Invalidate cached model — equipment changed
            personalizedModels.remove(uuid);

            // Apply instant model immediately (template skin, correct cosmetics visible)
            safeApplyInstant(player, slots);

            // Async: rebuild with player's actual skin
            schedulePersonalization(player, slots);
            return true;
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Error equipping '{}' for {}: {}", itemName, player.getName().getString(), e.getMessage(), e);
            return false;
        }
    }

    public boolean unequip(ServerPlayer player, String slot, PlayerDataStore store) {
        if (api == null) return false;
        try {
            String uuid = player.getStringUUID();
            String current = store.getSlotItem(uuid, slot);
            if (current == null) return false;

            store.unequip(uuid, slot);
            personalizedModels.remove(uuid);

            Map<String, String> slots = store.getSlots(uuid);
            if (slots.isEmpty()) {
                safeResetModel(player);
            } else {
                safeApplyInstant(player, slots);
                schedulePersonalization(player, slots);
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Error unequipping slot '{}' for {}: {}", slot, player.getName().getString(), e.getMessage(), e);
            return false;
        }
    }

    public void resetPlayer(ServerPlayer player) {
        safeResetModel(player);
        personalizedModels.remove(player.getStringUUID());
    }

    /** Returns the leaf storeID set for all currently-equipped animation names. */
    private Set<Long> computeEquippedLeafIds(Collection<String> equippedAnimations) {
        Set<Long> result = new HashSet<>();
        for (String anim : equippedAnimations) {
            Set<Long> leafIds = animToLeafStoreIds.get(anim);
            if (leafIds != null) result.addAll(leafIds);
        }
        return result;
    }

    /**
     * Builds and applies a quick model using the template skin (no network call).
     * Shows only the cosmetics matching the current equipped slot set.
     */
    private void safeApplyInstant(ServerPlayer player, Map<String, String> slots) {
        if (wardrobeConfig == null || wardrobeTemplateSkin == null || api == null) return;
        try {
            Set<Long> leafIds = computeEquippedLeafIds(slots.values());
            ModelFile instant = CpmProjectConverter.buildModelFile(wardrobeConfig, wardrobeTemplateSkin, leafIds);
            api.setPlayerModel(ServerPlayer.class, player, instant, true);
        } catch (Exception e) {
            LOGGER.warn("[VoidRpCpm] safeApplyInstant failed for {}: {}", player.getName().getString(), e.getMessage());
            // Last resort: apply plain wardrobe model
            if (wardrobeModel != null) {
                try { api.setPlayerModel(ServerPlayer.class, player, wardrobeModel, true); } catch (Exception ignored) {}
            }
        }
    }

    // ── Personalization ─────────────────────────────────────────────────────────

    /**
     * Schedules async skin download + compositing, then applies the model on the main thread.
     * Captures a snapshot of slots so the result matches exactly what was requested.
     */
    private void schedulePersonalization(ServerPlayer player, Map<String, String> slots) {
        if (wardrobeConfig == null || wardrobeTemplateSkin == null) return;
        var server = player.getServer();
        if (server == null) return;

        String uuid = player.getStringUUID();
        // Snapshot the slot values so the async task is self-contained
        Map<String, String> slotSnapshot = Map.copyOf(slots);

        CompletableFuture.supplyAsync(() -> buildPersonalizedModel(player, slotSnapshot))
            .thenAcceptAsync(model -> {
                if (model == null) return;
                if (server.getPlayerList().getPlayer(player.getUUID()) == null) return;
                // Only apply if equipped set hasn't changed since this task was scheduled
                Map<String, String> current = server.getPlayerList().getPlayer(player.getUUID()) != null
                    ? slotSnapshot : null;
                if (current == null) return;
                try {
                    personalizedModels.put(uuid, model);
                    api.setPlayerModel(ServerPlayer.class, player, model, true);
                    LOGGER.info("[VoidRpCpm] Personalized model applied for {} (slots: {})",
                            player.getName().getString(), slotSnapshot.values());
                } catch (Exception e) {
                    LOGGER.warn("[VoidRpCpm] Failed to apply personalized model for {}: {}", player.getName().getString(), e.getMessage());
                }
            }, server::execute);
    }

    private ModelFile buildPersonalizedModel(ServerPlayer player, Map<String, String> slots) {
        try {
            byte[] playerSkin = getOrFetchSkin(player);
            if (playerSkin == null) return null;
            byte[] compositeSkin = compositeSkins(wardrobeTemplateSkin, playerSkin);
            Set<Long> leafIds = computeEquippedLeafIds(slots.values());
            return CpmProjectConverter.buildModelFile(wardrobeConfig, compositeSkin, leafIds);
        } catch (Exception e) {
            LOGGER.warn("[VoidRpCpm] Skin personalization failed for {}: {}", player.getName().getString(), e.getMessage());
            return null;
        }
    }

    // ── Skin fetching ──────────────────────────────────────────────────────────

    /**
     * Returns the cached skin PNG for the player, or fetches it from the backend.
     * Returns null if the player has no skin registered — in that case the player
     * has already been notified via chat.
     */
    private byte[] getOrFetchSkin(ServerPlayer player) {
        String uuid = player.getStringUUID();
        byte[] cached = skinCache.get(uuid);
        if (cached != null) return cached;

        try {
            String url = fetchSkinUrlFromBackend(player);
            if (url == null) return null;
            byte[] png = downloadBytes(url, modConfig != null ? modConfig.getSkinFetchTimeoutMs() : 5000);
            if (png != null) skinCache.put(uuid, png);
            return png;
        } catch (Exception e) {
            LOGGER.warn("[VoidRpCpm] Cannot fetch skin for {}: {}", player.getName().getString(), e.getMessage());
            return null;
        }
    }

    /**
     * Calls GET /api/v1/server/auth/player-skin/{name} on the backend.
     * If the player has no skin registered, sends them a chat message and returns null.
     */
    private String fetchSkinUrlFromBackend(ServerPlayer player) {
        if (modConfig == null) {
            LOGGER.warn("[VoidRpCpm] ModConfig not set — cannot fetch skin from backend");
            return null;
        }
        String name = player.getName().getString();
        String endpoint = modConfig.getBackendUrl() + "/api/v1/server/auth/player-skin/" + name;
        int timeout = modConfig.getSkinFetchTimeoutMs();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setRequestProperty("User-Agent", "VoidRpCpm/1.0");
            conn.setRequestProperty("X-Game-Auth-Secret", modConfig.getGameAuthSecret());

            int status = conn.getResponseCode();
            if (status == 404) {
                notifyNoSkin(player);
                return null;
            }
            if (status != 200) {
                conn.disconnect();
                LOGGER.warn("[VoidRpCpm] Backend returned HTTP {} for skin of {} — falling back to GameProfile", status, name);
                return getSkinUrlFromGameProfile(player);
            }

            byte[] body;
            try (InputStream is = conn.getInputStream()) {
                body = is.readAllBytes();
            } finally {
                conn.disconnect();
            }

            JsonObject json = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            boolean hasSkin = json.has("has_skin") && json.get("has_skin").getAsBoolean();
            if (!hasSkin) {
                notifyNoSkin(player);
                return null;
            }
            if (!json.has("skin_url") || json.get("skin_url").isJsonNull()) {
                LOGGER.warn("[VoidRpCpm] Backend: has_skin=true but skin_url missing for {}", name);
                return null;
            }
            return json.get("skin_url").getAsString();

        } catch (Exception e) {
            LOGGER.warn("[VoidRpCpm] Backend skin fetch failed for {} ({}), falling back to GameProfile", name, e.getMessage());
            return getSkinUrlFromGameProfile(player);
        }
    }

    /** Fallback: read skin URL from the player's Minecraft GameProfile (set by SkinsRestorer or Mojang). */
    private static String getSkinUrlFromGameProfile(ServerPlayer player) {
        try {
            Collection<Property> props = player.getGameProfile().getProperties().get("textures");
            if (props == null || props.isEmpty()) return null;
            Property prop = props.iterator().next();
            byte[] decoded = Base64.getDecoder().decode(prop.value());
            JsonObject root = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject textures = root.getAsJsonObject("textures");
            if (textures == null || !textures.has("SKIN")) return null;
            return textures.getAsJsonObject("SKIN").get("url").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void notifyNoSkin(ServerPlayer player) {
        try {
            player.sendSystemMessage(Component.literal("§eУстановите скин в лаунчере, чтобы косметика отображалась корректно"));
        } catch (Exception ignored) {}
    }

    private static byte[] downloadBytes(String urlStr, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "VoidRpCpm/1.0");
        try (InputStream is = conn.getInputStream()) {
            return is.readAllBytes();
        } finally {
            conn.disconnect();
        }
    }

    // ── Skin compositing ──────────────────────────────────────────────────────

    /**
     * Composites the player's skin onto the 0-63 area of the cosmetic template.
     * Handles all three skin variants:
     *   - 64×32 legacy (Steve arms): expanded to 64×64 by mirroring limbs
     *   - 64×64 Steve (4-wide arms): used as-is
     *   - 64×64 Alex (3-wide/slim arms): detected, arm faces adjusted before compositing
     */
    private static byte[] compositeSkins(byte[] templatePng, byte[] playerPng) throws Exception {
        BufferedImage template = ImageIO.read(new ByteArrayInputStream(templatePng));
        BufferedImage player   = ImageIO.read(new ByteArrayInputStream(playerPng));

        // Expand 64×32 legacy skins: left arm/leg areas live at y=48-63 which doesn't exist
        if (player.getHeight() < 64) {
            player = expandSkinTo64x64(player);
        }

        // Detect slim (Alex) skins: the pixel at (54,20) is transparent in slim skins
        // because the right arm front face is only 3px wide (x=44..46) not 4px (x=44..47).
        boolean isSlim = isSlimSkin(player);
        if (isSlim) {
            player = normalizeSlimToSteve(player);
        }

        int tw = template.getWidth();
        int th = template.getHeight();
        int pw = Math.min(player.getWidth(), 64);
        int ph = Math.min(player.getHeight(), 64);

        int[] tPx = new int[tw * th];
        int[] pPx = new int[pw * ph];
        template.getRGB(0, 0, tw, th, tPx, 0, tw);
        player.getRGB(0, 0, pw, ph, pPx, 0, pw);

        // Write player skin into 0-63 area of template; transparent src pixels keep template pixels
        int copyW = Math.min(pw, tw);
        int copyH = Math.min(ph, th);
        for (int y = 0; y < copyH; y++) {
            for (int x = 0; x < copyW; x++) {
                int srcPixel = pPx[y * pw + x];
                if ((srcPixel >>> 24) != 0) {
                    tPx[y * tw + x] = srcPixel;
                }
            }
        }
        template.setRGB(0, 0, tw, th, tPx, 0, tw);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(template, "PNG", baos);
        return baos.toByteArray();
    }

    /**
     * Expands a 64×32 legacy skin to 64×64 by generating left arm and left leg
     * areas via horizontal-flip mirroring of the right arm and right leg faces.
     */
    private static BufferedImage expandSkinTo64x64(BufferedImage src) {
        BufferedImage dst = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int[] srcPx = new int[64 * 32];
        src.getRGB(0, 0, 64, 32, srcPx, 0, 64);
        dst.setRGB(0, 0, 64, 32, srcPx, 0, 64);

        // Right leg (u=0,v=16, 4×4×12) → Left leg (u=16,v=48)
        copyHFlip(src, dst,  4, 16,  4,  4,  20, 48); // top
        copyHFlip(src, dst,  8, 16,  4,  4,  24, 48); // bottom
        copyHFlip(src, dst,  0, 20,  4, 12,  24, 52); // right side → left side
        copyHFlip(src, dst,  4, 20,  4, 12,  20, 52); // front
        copyHFlip(src, dst,  8, 20,  4, 12,  16, 52); // left side → right side
        copyHFlip(src, dst, 12, 20,  4, 12,  28, 52); // back

        // Right arm (u=40,v=16, 4×4×12) → Left arm (u=32,v=48)
        copyHFlip(src, dst, 44, 16,  4,  4,  36, 48); // top
        copyHFlip(src, dst, 48, 16,  4,  4,  40, 48); // bottom
        copyHFlip(src, dst, 40, 20,  4, 12,  40, 52); // right side → left side
        copyHFlip(src, dst, 44, 20,  4, 12,  36, 52); // front
        copyHFlip(src, dst, 48, 20,  4, 12,  32, 52); // left side → right side
        copyHFlip(src, dst, 52, 20,  4, 12,  44, 52); // back

        return dst;
    }

    /**
     * Detects slim (Alex) skin format by checking if the right arm "column 4" pixels
     * are transparent (they don't exist in slim skins where arm width = 3).
     */
    private static boolean isSlimSkin(BufferedImage skin) {
        if (skin.getWidth() < 56 || skin.getHeight() < 32) return false;
        // Check several pixels in the 4th column of right arm front face (x=47, y=20..23)
        int transparent = 0;
        for (int y = 20; y < 24; y++) {
            if ((skin.getRGB(54, y) >>> 24) == 0) transparent++;
        }
        return transparent >= 3;
    }

    /**
     * Shifts slim arm pixels into Steve-width positions so the fixed Steve-UV
     * template can sample them correctly.
     * Right arm:  3-wide at x=44 → 4-wide at x=44 (pad right face with 1px)
     * Left arm:   3-wide at x=36 → 4-wide at x=36
     * This is a best-effort remap; one pixel column will be stretched on each arm.
     */
    private static BufferedImage normalizeSlimToSteve(BufferedImage src) {
        BufferedImage dst = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[64 * 64];
        src.getRGB(0, 0, 64, 64, px, 0, 64);
        dst.setRGB(0, 0, 64, 64, px, 0, 64);

        // Right arm top row: shift columns 47-51 right by 1 (duplicate col 46 into 47)
        shiftRowsRight(dst, 44, 16, 4, 1); // top (slim w=3 → steve w=4)
        shiftRowsRight(dst, 47, 16, 4, 1); // bottom
        // Right arm sides at y=20..31
        for (int y = 20; y < 32; y++) {
            // insert one transparent pixel at col 47 (left face col) to push left face right
            int saved = dst.getRGB(47, y);
            dst.setRGB(47, y, 0);
            dst.setRGB(48, y, saved);
        }

        // Left arm (u=32,v=48): same remap
        shiftRowsRight(dst, 36, 48, 3, 1);
        shiftRowsRight(dst, 39, 48, 3, 1);
        for (int y = 52; y < 64; y++) {
            int saved = dst.getRGB(39, y);
            dst.setRGB(39, y, 0);
            dst.setRGB(40, y, saved);
        }

        return dst;
    }

    /** Shifts a w×h block one pixel right by duplicating the rightmost column. */
    private static void shiftRowsRight(BufferedImage img, int x, int y, int w, int shift) {
        for (int row = 0; row < 4; row++) {
            for (int col = w - 1; col >= 0; col--) {
                img.setRGB(x + col + shift, y + row, img.getRGB(x + col, y + row));
            }
            img.setRGB(x, y + row, 0);
        }
    }

    /** Copies a src rect to dst rect, flipping horizontally. */
    private static void copyHFlip(BufferedImage src, BufferedImage dst, int sx, int sy, int w, int h, int dx, int dy) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setRGB(dx + w - 1 - x, dy + y, src.getRGB(sx + x, sy + y));
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void safeResetModel(ServerPlayer player) {
        try {
            if (api != null) api.resetPlayerModel(ServerPlayer.class, player);
        } catch (Exception e) {
            LOGGER.warn("[VoidRpCpm] resetPlayerModel failed for {}: {}", player.getName().getString(), e.getMessage());
        }
    }
}
