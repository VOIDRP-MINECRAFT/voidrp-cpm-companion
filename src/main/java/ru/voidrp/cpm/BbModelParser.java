package ru.voidrp.cpm;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

/**
 * Parses Blockbench .bbmodel files and extracts cosmetic name → slot mappings.
 *
 * Naming convention: <slot>_<name> or <slot>-<name>
 *   head, hat                    → "head"
 *   body, chest, cape            → "body"
 *   legs, pants, leggings        → "legs"
 *   feet, boots                  → "feet"
 *   wings, tail, accessory, misc → "accessory"
 */
public class BbModelParser {

    private static final Logger LOGGER = LoggerFactory.getLogger("VoidRpCpm");
    private static final Gson GSON = new Gson();

    private static final Map<String, String> PREFIX_TO_SLOT = new LinkedHashMap<>();

    static {
        PREFIX_TO_SLOT.put("head", "head");
        PREFIX_TO_SLOT.put("hat", "head");
        PREFIX_TO_SLOT.put("body", "body");
        PREFIX_TO_SLOT.put("chest", "body");
        PREFIX_TO_SLOT.put("cape", "body");
        PREFIX_TO_SLOT.put("legs", "legs");
        PREFIX_TO_SLOT.put("pants", "legs");
        PREFIX_TO_SLOT.put("leggings", "legs");
        PREFIX_TO_SLOT.put("feet", "feet");
        PREFIX_TO_SLOT.put("boots", "feet");
        PREFIX_TO_SLOT.put("wings", "accessory");
        PREFIX_TO_SLOT.put("tail", "accessory");
        PREFIX_TO_SLOT.put("accessory", "accessory");
        PREFIX_TO_SLOT.put("misc", "accessory");
    }

    public static Map<String, String> parse(File bbmodelFile) {
        Map<String, String> result = new LinkedHashMap<>();
        if (bbmodelFile == null || !bbmodelFile.exists()) {
            LOGGER.warn("[VoidRpCpm] .bbmodel file not found: {}", bbmodelFile);
            return result;
        }

        try (Reader r = new FileReader(bbmodelFile)) {
            JsonElement rootEl = GSON.fromJson(r, JsonElement.class);
            if (rootEl == null || !rootEl.isJsonObject()) {
                LOGGER.warn("[VoidRpCpm] {} is not a valid JSON object", bbmodelFile.getName());
                return result;
            }
            JsonObject root = rootEl.getAsJsonObject();

            Set<String> names = new LinkedHashSet<>();

            // Read animation names — these are what CPM uses via playAnimation()
            if (root.has("animations")) {
                JsonElement animEl = root.get("animations");
                if (animEl.isJsonArray()) {
                    for (JsonElement el : animEl.getAsJsonArray()) {
                        extractName(el, names);
                    }
                }
            }

            if (names.isEmpty()) {
                LOGGER.warn("[VoidRpCpm] {} has no animations — make sure cosmetics are defined as animations in Blockbench CPM plugin", bbmodelFile.getName());
            }

            for (String name : names) {
                String slot = resolveSlot(name);
                result.put(name, slot);
            }

            if (!result.isEmpty()) {
                LOGGER.info("[VoidRpCpm] Parsed {}: {} cosmetics", bbmodelFile.getName(), result.size());
                result.forEach((n, s) -> LOGGER.info("[VoidRpCpm]   {} → {}", n, s));
            } else {
                LOGGER.warn("[VoidRpCpm] {} parsed but no cosmetics found (check animation names)", bbmodelFile.getName());
            }

        } catch (JsonSyntaxException e) {
            LOGGER.error("[VoidRpCpm] JSON syntax error in {}: {}", bbmodelFile.getName(), e.getMessage());
        } catch (IOException e) {
            LOGGER.error("[VoidRpCpm] Cannot read {}: {}", bbmodelFile.getName(), e.getMessage());
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Unexpected error parsing {}: {}", bbmodelFile.getName(), e.getMessage(), e);
        }
        return result;
    }

    public static Map<String, String> scanDirectory(File dir) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (dir == null || !dir.exists() || !dir.isDirectory()) return merged;
        try {
            File[] files = dir.listFiles(f -> f.getName().endsWith(".bbmodel"));
            if (files == null || files.length == 0) return merged;
            for (File f : files) {
                try {
                    merged.putAll(parse(f));
                } catch (Exception e) {
                    LOGGER.error("[VoidRpCpm] Skipping {}: {}", f.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.error("[VoidRpCpm] Error scanning directory {}: {}", dir.getPath(), e.getMessage());
        }
        return merged;
    }

    private static void extractName(JsonElement el, Set<String> out) {
        try {
            if (!el.isJsonObject()) return;
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("name")) return;
            JsonElement nameEl = obj.get("name");
            if (!nameEl.isJsonPrimitive()) return;
            String name = nameEl.getAsString().trim();
            if (!name.isEmpty() && !name.startsWith("_")) out.add(name);
        } catch (Exception e) {
            // skip malformed entry silently
        }
    }

    private static String resolveSlot(String name) {
        try {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            int sep = indexOfSeparator(lower);
            String prefix = sep > 0 ? lower.substring(0, sep) : lower;
            String slot = PREFIX_TO_SLOT.get(prefix);
            return slot != null ? slot : "accessory";
        } catch (Exception e) {
            return "accessory";
        }
    }

    private static int indexOfSeparator(String s) {
        int u = s.indexOf('_');
        int h = s.indexOf('-');
        if (u < 0) return h;
        if (h < 0) return u;
        return Math.min(u, h);
    }
}
