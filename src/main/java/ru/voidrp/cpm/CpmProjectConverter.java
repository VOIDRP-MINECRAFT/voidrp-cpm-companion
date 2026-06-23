package ru.voidrp.cpm;

import com.google.gson.*;
import com.tom.cpm.shared.io.ModelFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Converts a .cpmproject (ZIP/JSON) to a binary CPM ModelFile in memory.
 *
 * CPM binary format (outer ModelFile):
 *   [0x53] + checksummed { writeUTF(name) + writeUTF(desc) + writeByteArray(dataBlock)
 *                          + writeByteArray(empty) + writeNextBlock(empty) }
 *            + 2-byte checksum
 *
 * dataBlock binary format:
 *   [0x53] + checksummed { [type=5  SKIN]  + [VarInt(len)] + [Short(w)+Short(h)+VarInt(pngLen)+PNG]
 *                          [type=21 CUBES] + [VarInt(len)] + [cubes_content]
 *                          [type=0  END]   + [VarInt(0)] }
 *            + 2-byte checksum
 *
 * cubes_content: VarInt(cube_count) + [each cube binary]
 *
 * Per-cube binary: see encodeCube()
 *
 * Cosmetic visibility is controlled per-build: caller passes the set of leaf cube storeIDs
 * that belong to currently-equipped cosmetics. Only those cubes get show=true.
 * Standard bone proxies (name == bone_id) are always visible.
 */
public class CpmProjectConverter {
    private static final Logger LOGGER = LoggerFactory.getLogger("VoidRpCpm");

    // PlayerModelParts ordinals matching HEAD=0, BODY=1, LEFT_ARM=2, RIGHT_ARM=3, LEFT_LEG=4, RIGHT_LEG=5
    private static final Map<String, Integer> BONE_IDS = new HashMap<>();
    static {
        BONE_IDS.put("head",      0);
        BONE_IDS.put("body",      1);
        BONE_IDS.put("left_arm",  2);
        BONE_IDS.put("right_arm", 3);
        BONE_IDS.put("left_leg",  4);
        BONE_IDS.put("right_leg", 5);
    }

    private static final int MODEL_PART_SKIN  = 5;
    private static final int MODEL_PART_CUBES = 21;
    private static final int MODEL_PART_END   = 0;

    /**
     * Raw contents extracted from a .cpmproject ZIP.
     * animToLeafStoreIds: cosmetic animation name → set of leaf cube storeIDs controlled by it.
     */
    public record ZipContents(
        JsonObject config,
        byte[] skinPng,
        Map<String, Set<Long>> animToLeafStoreIds
    ) {}

    public static ModelFile fromCpmProject(File file) throws IOException {
        byte[] raw = readFile(file);
        if (raw.length < 2) throw new IOException("File too small: " + file.getName());

        if ((raw[0] & 0xFF) == 0x50 && (raw[1] & 0xFF) == 0x4B) {
            ZipContents contents = extractZip(raw, file.getName());
            byte[] modelBinary = buildModelBinary(contents.config(), contents.skinPng(), null);
            LOGGER.info("[VoidRpCpm] Converted {} to CPM binary ({} bytes, skin={})",
                    file.getName(), modelBinary.length,
                    contents.skinPng() != null ? contents.skinPng().length + "b" : "none");
            return ModelFile.load(new ByteArrayInputStream(modelBinary));
        }
        if ((raw[0] & 0xFF) == 0x53) {
            return ModelFile.load(new ByteArrayInputStream(raw));
        }
        throw new IOException("Unknown format: " + file.getName());
    }

    /** Extracts config.json + skin.png + animation mappings from a .cpmproject ZIP. */
    public static ZipContents extractZip(byte[] zipBytes, String fileName) throws IOException {
        byte[] configJson = null;
        byte[] skinPng = null;
        Map<String, byte[]> animJsons = new LinkedHashMap<>();

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if ("config.json".equals(name)) {
                    configJson = readStream(zis);
                } else if ("skin.png".equals(name)) {
                    skinPng = readStream(zis);
                } else if (name.startsWith("animations/") && name.endsWith(".json")) {
                    animJsons.put(name, readStream(zis));
                }
                zis.closeEntry();
            }
        }
        if (configJson == null) throw new IOException("No config.json in " + fileName);
        JsonObject config = JsonParser.parseString(new String(configJson, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, Set<Long>> animToLeafs = buildAnimToLeafMap(config, animJsons);
        LOGGER.info("[VoidRpCpm] Loaded animations: {}", animToLeafs.keySet());
        return new ZipContents(config, skinPng, animToLeafs);
    }

    /**
     * Builds a ModelFile showing only the cosmetics whose leaf storeIDs are in equippedLeafIds.
     * Pass null to show all (fallback / template model).
     */
    public static ModelFile buildModelFile(JsonObject config, byte[] skinPng, Set<Long> equippedLeafIds) throws IOException {
        byte[] binary = buildModelBinary(config, skinPng, equippedLeafIds);
        return ModelFile.load(new ByteArrayInputStream(binary));
    }

    /** Backward-compat: builds a model showing all cosmetics (pass null filter). */
    public static ModelFile buildModelFile(JsonObject config, byte[] skinPng) throws IOException {
        return buildModelFile(config, skinPng, null);
    }

    // ── Animation → storeID mapping ────────────────────────────────────────────

    /**
     * Reads animation JSON files from the zip and builds:
     *   animationName → Set of leaf cube storeIDs that animation controls.
     *
     * Ignores internal CPM animations (names starting with "$").
     */
    private static Map<String, Set<Long>> buildAnimToLeafMap(JsonObject config, Map<String, byte[]> animJsons) {
        Map<String, Set<Long>> result = new LinkedHashMap<>();

        for (byte[] bytes : animJsons.values()) {
            try {
                JsonObject anim = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                String name = anim.has("name") ? anim.get("name").getAsString() : "";
                if (name.isEmpty() || name.startsWith("$")) continue;

                // Collect leaf storeIDs from ALL elements whose name equals animName
                // or starts with animName + "_". This covers split left/right-leg patterns
                // where the designer uses one animation name across multiple bones.
                Set<Long> leafIds = new HashSet<>();
                JsonArray elements = config.getAsJsonArray("elements");
                if (elements != null) {
                    for (JsonElement el : elements) {
                        if (el.isJsonObject())
                            walkForAnimName(el.getAsJsonObject(), name, leafIds);
                    }
                }

                if (!leafIds.isEmpty()) result.put(name, leafIds);
            } catch (Exception e) {
                // skip malformed animation file
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Walks the node tree. When a node's name matches the animation name (exact or with _ suffix),
     * collects all leaf storeIDs under it. Otherwise recurses into children.
     */
    private static void walkForAnimName(JsonObject node, String animName, Set<Long> out) {
        String nodeName = node.has("name") ? node.get("name").getAsString() : "";
        if (nodeName.equals(animName) || nodeName.startsWith(animName + "_")) {
            collectLeafIds(node, out);
            return;
        }
        JsonArray children = (node.has("children") && node.get("children").isJsonArray())
            ? node.getAsJsonArray("children") : null;
        if (children != null) {
            for (JsonElement ch : children) {
                if (ch.isJsonObject()) walkForAnimName(ch.getAsJsonObject(), animName, out);
            }
        }
    }

    /** Collects all leaf (non-group) storeIDs under this node recursively. */
    private static void collectLeafIds(JsonObject node, Set<Long> out) {
        JsonArray children = (node.has("children") && node.get("children").isJsonArray())
            ? node.getAsJsonArray("children") : null;
        boolean hasKids = children != null && !children.isEmpty();
        if (!hasKids) {
            if (node.has("storeID")) out.add(node.get("storeID").getAsLong());
            return;
        }
        for (JsonElement ch : children) {
            if (ch.isJsonObject()) collectLeafIds(ch.getAsJsonObject(), out);
        }
    }

    /**
     * Traverses config.json and builds a map from every storeID to the set of
     * leaf-cube storeIDs under it. For leaf cubes, maps to themselves.
     */
    private static Map<Long, Set<Long>> buildStoreIdToLeafsMap(JsonObject config) {
        Map<Long, Set<Long>> map = new HashMap<>();
        JsonArray elements = config.getAsJsonArray("elements");
        if (elements == null) return map;
        for (JsonElement el : elements) {
            if (el.isJsonObject()) collectLeafs(el.getAsJsonObject(), map);
        }
        return map;
    }

    /**
     * DFS: returns the set of leaf storeIDs under this node and populates map.
     */
    private static Set<Long> collectLeafs(JsonObject node, Map<Long, Set<Long>> map) {
        long myId = node.has("storeID") ? node.get("storeID").getAsLong() : -1L;
        JsonArray childrenArr = (node.has("children") && node.get("children").isJsonArray())
            ? node.getAsJsonArray("children") : null;

        Set<Long> myLeafs = new HashSet<>();
        boolean hasKids = false;

        if (childrenArr != null) {
            for (JsonElement childEl : childrenArr) {
                if (!childEl.isJsonObject()) continue;
                myLeafs.addAll(collectLeafs(childEl.getAsJsonObject(), map));
                hasKids = true;
            }
        }

        if (!hasKids && myId > 0) {
            // Leaf cube
            myLeafs.add(myId);
            map.put(myId, Set.of(myId));
        } else if (hasKids && myId > 0) {
            // Group node: maps to all its leaf descendants
            map.put(myId, new HashSet<>(myLeafs));
        }
        return myLeafs;
    }

    // ── Binary model building ───────────────────────────────────────────────────

    private static byte[] buildModelBinary(JsonObject config, byte[] skinPng, Set<Long> equippedLeafIds) throws IOException {
        byte[] dataBlock = buildDataBlock(config, skinPng, equippedLeafIds);
        ByteArrayOutputStream payloadBuf = new ByteArrayOutputStream();
        writeUTF(payloadBuf, "");
        writeUTF(payloadBuf, "");
        writeByteArray(payloadBuf, dataBlock);
        writeByteArray(payloadBuf, new byte[0]);
        writeNextBlock(payloadBuf, new byte[0]);
        return checksumWrap(payloadBuf.toByteArray());
    }

    private static byte[] buildDataBlock(JsonObject config, byte[] skinPng, Set<Long> equippedLeafIds) throws IOException {
        byte[] cubesContent = buildCubesContent(config, equippedLeafIds);
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        if (skinPng != null && skinPng.length > 0) {
            int[] dims = parsePngDimensions(skinPng);
            byte[] skinContent = buildSkinBlockContent(skinPng, dims[0], dims[1]);
            inner.write(MODEL_PART_SKIN);
            writeNextBlock(inner, skinContent);
        }
        inner.write(MODEL_PART_CUBES);
        writeNextBlock(inner, cubesContent);
        inner.write(MODEL_PART_END);
        writeVarInt(inner, 0);
        return checksumWrap(inner.toByteArray());
    }

    /** SKIN block content: Short(width) + Short(height) + VarInt(pngLen) + PNG bytes */
    private static byte[] buildSkinBlockContent(byte[] pngBytes, int width, int height) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(buf);
        dos.writeShort(width);
        dos.writeShort(height);
        dos.flush();
        writeByteArray(buf, pngBytes);
        return buf.toByteArray();
    }

    /** Reads PNG width/height from the IHDR chunk at bytes 16–23. */
    private static int[] parsePngDimensions(byte[] png) {
        if (png.length < 24) return new int[]{64, 64};
        int w = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
        int h = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
        return new int[]{w, h};
    }

    private static byte[] buildCubesContent(JsonObject config, Set<Long> equippedLeafIds) throws IOException {
        List<CubeEntry> cubes = parseCubes(config, equippedLeafIds);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeVarInt(buf, cubes.size());
        for (CubeEntry ce : cubes) {
            encodeCube(buf, ce);
        }
        return buf.toByteArray();
    }

    private static List<CubeEntry> parseCubes(JsonObject config, Set<Long> equippedLeafIds) {
        List<CubeEntry> result = new ArrayList<>();
        JsonArray elements = config.getAsJsonArray("elements");
        if (elements == null) return result;

        for (JsonElement el : elements) {
            JsonObject element = el.getAsJsonObject();
            String boneId = element.has("id") ? element.get("id").getAsString() : "";
            Integer parentId = BONE_IDS.get(boneId);
            if (parentId == null) continue;
            parseCubesRecursive(element, parentId, boneId, equippedLeafIds, new float[]{0,0,0}, result);
        }
        return result;
    }

    /**
     * Recursively collects leaf cubes.
     * posAcc: accumulated position offset from parent groups.
     *
     * Visibility rules:
     *  - Standard bone proxy (child name == boneName): always shown
     *  - equippedLeafIds == null: show from config (fallback/template)
     *  - Cosmetic cube: visible only if its storeID is in equippedLeafIds
     */
    private static void parseCubesRecursive(JsonObject node, int parentId, String boneName,
                                             Set<Long> equippedLeafIds, float[] posAcc, List<CubeEntry> result) {
        JsonArray children = node.getAsJsonArray("children");
        if (children == null) return;

        for (JsonElement childEl : children) {
            if (!childEl.isJsonObject()) continue;
            JsonObject child = childEl.getAsJsonObject();

            float[] childPos = parseVec3(child, "pos");
            float[] size = parseVec3(child, "size");
            boolean isGroup = size[0] == 0 && size[1] == 0 && size[2] == 0
                    && child.has("children") && child.get("children").isJsonArray();

            if (isGroup) {
                // Accumulate group position offset for descendants
                float[] newAcc = {posAcc[0] + childPos[0], posAcc[1] + childPos[1], posAcc[2] + childPos[2]};
                parseCubesRecursive(child, parentId, boneName, equippedLeafIds, newAcc, result);
            } else {
                CubeEntry ce = new CubeEntry();
                ce.parentId = parentId;
                ce.name     = optString(child, "name", "");
                ce.texture  = optBool(child, "texture", false);
                ce.texSize  = optInt(child, "textureSize", 1);
                ce.u        = optInt(child, "u", 0);
                ce.v        = optInt(child, "v", 0);
                // Apply accumulated group offsets to the cube's own position
                ce.pos      = new float[]{childPos[0] + posAcc[0], childPos[1] + posAcc[1], childPos[2] + posAcc[2]};
                ce.rotation = parseVec3(child, "rotation");
                ce.size     = size;
                ce.offset   = parseVec3(child, "offset");

                boolean isStandardProxy = ce.name.equals(boneName);
                if (isStandardProxy || equippedLeafIds == null) {
                    ce.show = optBool(child, "show", true);
                } else {
                    long storeId = child.has("storeID") ? child.get("storeID").getAsLong() : -1L;
                    ce.show = equippedLeafIds.contains(storeId);
                }

                result.add(ce);
            }
        }
    }

    private static void encodeCube(OutputStream out, CubeEntry ce) throws IOException {
        float[] sz = ce.size;
        boolean hasSize      = sz[0] != 0 || sz[1] != 0 || sz[2] != 0;
        boolean hasTexture   = hasSize && ce.texSize != 0;
        boolean isHidden     = !ce.show;
        boolean hasMeshScale = false;
        boolean hasMultiTex  = hasTexture && ce.texSize != 1;
        boolean hasMcScale   = false;
        boolean hasScale     = false;

        boolean[] flags = {hasSize, hasTexture, isHidden, hasMeshScale, hasMultiTex, hasMcScale, hasScale};
        int flagByte = 0;
        for (int i = flags.length - 1; i >= 0; i--) {
            flagByte = (flagByte << 1) | (flags[i] ? 1 : 0);
        }

        out.write(flagByte);
        writeVarInt(out, ce.parentId);
        writeVarVec3(out, ce.pos);
        writeAngle(out, ce.rotation);

        if (hasSize) {
            writeVarVec3(out, ce.size);
            writeVarVec3(out, ce.offset);

            if (hasTexture) {
                if (hasMultiTex) {
                    out.write(ce.texSize);
                }
                writeVarInt(out, ce.u);
                writeVarInt(out, ce.v);
            } else {
                out.write(0);
                out.write(0);
                out.write(0);
            }
        }
    }

    // ── Binary primitives ───────────────────────────────────────────────────────

    /** Unsigned VarInt (7 bits/byte, MSB = has-more) */
    private static void writeVarInt(OutputStream out, int val) throws IOException {
        while (true) {
            int toWrite = val & 0x7F;
            val >>>= 7;
            if (val != 0) toWrite |= 0x80;
            out.write(toWrite);
            if (val == 0) break;
        }
    }

    /** Signed VarInt: sign bit in bit-6 of the first byte, abs(val) in 6+7n bits */
    private static void writeSignedVarInt(OutputStream out, int val) throws IOException {
        int sign = (val < 0) ? 0x40 : 0;
        val = Math.abs(val);
        boolean first = true;
        while (true) {
            int toWrite;
            if (first) {
                toWrite = (val & 0x3F) | sign;
                val >>= 6;
                first = false;
            } else {
                toWrite = val & 0x7F;
                val >>= 7;
            }
            if (val != 0) toWrite |= 0x80;
            out.write(toWrite);
            if (val == 0) break;
        }
    }

    /** VarFloat = SignedVarInt(round(f * 682)) */
    private static void writeVarFloat(OutputStream out, float f) throws IOException {
        writeSignedVarInt(out, (int)(f * 682f));
    }

    /** 3× VarFloat */
    private static void writeVarVec3(OutputStream out, float[] v) throws IOException {
        writeVarFloat(out, v[0]);
        writeVarFloat(out, v[1]);
        writeVarFloat(out, v[2]);
    }

    /** Rotation encoded as 3 big-endian unsigned shorts: short = degree/360*65535, clamped [0,65535] */
    private static void writeAngle(OutputStream out, float[] v) throws IOException {
        for (float deg : v) {
            int s = (int)(deg / 360f * 65535f);
            s = Math.max(0, Math.min(65535, s));
            out.write((s >> 8) & 0xFF);
            out.write(s & 0xFF);
        }
    }

    /** VarInt(len) + UTF-8 bytes */
    private static void writeUTF(OutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    /** VarInt(len) + bytes */
    private static void writeByteArray(OutputStream out, byte[] data) throws IOException {
        writeVarInt(out, data.length);
        out.write(data);
    }

    /** VarInt(len) + bytes (block wrapper, same as writeByteArray) */
    private static void writeNextBlock(OutputStream out, byte[] data) throws IOException {
        writeVarInt(out, data.length);
        out.write(data);
    }

    /**
     * Wraps payload in: [0x53] + [payload] + [checksum_hi] + [checksum_lo]
     * Checksum = 16-bit sum of payload bytes only (not including 0x53 or checksum).
     */
    private static byte[] checksumWrap(byte[] payload) {
        int sum = 0;
        for (byte b : payload) {
            sum += b & 0xFF;
        }
        sum &= 0xFFFF;
        byte[] result = new byte[1 + payload.length + 2];
        result[0] = 0x53;
        System.arraycopy(payload, 0, result, 1, payload.length);
        result[result.length - 2] = (byte)((sum >> 8) & 0xFF);
        result[result.length - 1] = (byte)(sum & 0xFF);
        return result;
    }

    // ── JSON helpers ────────────────────────────────────────────────────────────

    private static float[] parseVec3(JsonObject obj, String key) {
        if (!obj.has(key)) return new float[]{0, 0, 0};
        JsonObject v = obj.getAsJsonObject(key);
        return new float[]{
            v.has("x") ? v.get("x").getAsFloat() : 0f,
            v.has("y") ? v.get("y").getAsFloat() : 0f,
            v.has("z") ? v.get("z").getAsFloat() : 0f
        };
    }

    private static String optString(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }

    private static boolean optBool(JsonObject o, String k, boolean def) {
        return o.has(k) ? o.get(k).getAsBoolean() : def;
    }

    private static int optInt(JsonObject o, String k, int def) {
        return o.has(k) ? o.get(k).getAsInt() : def;
    }

    // ── IO helpers ──────────────────────────────────────────────────────────────

    private static byte[] readFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            return fis.readAllBytes();
        }
    }

    private static byte[] readStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) >= 0) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    // ── Data class ──────────────────────────────────────────────────────────────

    private static class CubeEntry {
        int parentId;
        String name;
        boolean show;
        boolean texture;
        int texSize;
        int u, v;
        float[] pos, rotation, size, offset;
    }
}
