package com.example.dwpreviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.example.dwpreviewer.SkinPreviewData;
import com.mcbox.pesdk.util.LauncherMiscUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes3.dex */
final class SkinPackageLoader {
    private static final int IMAGE_PAYLOAD_OFFSET = 1350;
    private static final byte[] MAGIC = "19311931EE".getBytes(StandardCharsets.US_ASCII);

    private SkinPackageLoader() {
    }

    static SkinPreviewData load(Context context, Uri uri) throws Exception {
        byte[] bArrFirstEntry;
        List<SkinPreviewData.Part> listStandardModel;
        boolean z;
        byte[] all = readAll(context, context.getContentResolver().openInputStream(uri));
        Map<String, byte[]> mapUnzip = isZip(all) ? unzip(all) : null;
        byte[] bArrFindModelJson = null;
        byte[] value = null;
        String lastPathSegment = uri.getLastPathSegment();
        boolean z2 = false;
        boolean zLooksInternationalDuowan = false;
        boolean z3 = true;
        if (mapUnzip != null) {
            zLooksInternationalDuowan = looksInternationalDuowan(mapUnzip);
            bArrFindModelJson = findModelJson(mapUnzip);
            for (Map.Entry<String, byte[]> entry : mapUnzip.entrySet()) {
                if (entry.getKey().toLowerCase(Locale.US).endsWith(".blur")) {
                    value = entry.getValue();
                    z2 = z2 || startsWithMagic(value, 0);
                }
            }
            if (bArrFindModelJson != null) {
                lastPathSegment = modelJsonName(mapUnzip, bArrFindModelJson);
            }
            bArrFirstEntry = firstEntry(mapUnzip, ".png", ".jpg", ".jpeg");
            if (bArrFirstEntry == null) {
                throw error(context, R.string.error_no_texture);
            }
        } else {
            bArrFirstEntry = all;
        }
        boolean z4 = z2 || encryptedImageOffset(bArrFirstEntry) >= 0;
        byte[] bArrDecodeMaybeEncryptedImage = decodeMaybeEncryptedImage(context, bArrFirstEntry);
        Bitmap bitmapDecodeByteArray = BitmapFactory.decodeByteArray(bArrDecodeMaybeEncryptedImage, 0, bArrDecodeMaybeEncryptedImage.length);
        if (bitmapDecodeByteArray == null) {
            throw error(context, R.string.error_bad_texture);
        }
        if (bArrFindModelJson == null) {
            bitmapDecodeByteArray = normalizeStandardSkinBitmap(bitmapDecodeByteArray);
            listStandardModel = standardModel(bitmapDecodeByteArray.getHeight(), false);
            z = true;
        } else {
            JsonDecodeResult jsonDecodeResultDecodeModelJson = decodeModelJson(context, bArrFindModelJson, value);
            if (!z4 && !jsonDecodeResultDecodeModelJson.encrypted) {
                z3 = false;
            }
            z4 = z3;
            listStandardModel = parseModel(context, new String(jsonDecodeResultDecodeModelJson.data, StandardCharsets.UTF_8), bitmapDecodeByteArray.getHeight());
            z = false;
        }
        return new SkinPreviewData(bitmapDecodeByteArray, listStandardModel, lastPathSegment == null ? "skin" : lastPathSegment, z4, z, false, zLooksInternationalDuowan ? 1 : 0);
    }

    private static Bitmap normalizeStandardSkinBitmap(Bitmap bitmap) {
        Bitmap mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        int width = mutable.getWidth();
        int height = mutable.getHeight();
        makeOpaque(mutable, 0, 0, 32, 16);
        makeOpaque(mutable, 16, 16, 24, 16);
        makeOpaque(mutable, 40, 16, 16, 16);
        makeOpaque(mutable, 0, 16, 16, 16);
        if (height > 32) {
            makeOpaque(mutable, 32, 48, 16, 16);
            makeOpaque(mutable, 16, 48, 16, 16);
        } else if (width > 32) {
            makeOpaque(mutable, 40, 16, 16, 16);
            makeOpaque(mutable, 0, 16, 16, 16);
        }
        return mutable;
    }

    private static void makeOpaque(Bitmap bitmap, int left, int top, int width, int height) {
        int maxX = Math.min(bitmap.getWidth(), left + width);
        int maxY = Math.min(bitmap.getHeight(), top + height);
        for (int y = Math.max(0, top); y < maxY; y++) {
            for (int x = Math.max(0, left); x < maxX; x++) {
                bitmap.setPixel(x, y, bitmap.getPixel(x, y) | 0xff000000);
            }
        }
    }

    private static Map<String, byte[]> unzip(byte[] data) throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data));
        while (true) {
            try {
                ZipEntry entry = zip.getNextEntry();
                if (entry != null) {
                    if (!entry.isDirectory() && !entry.getName().contains("__MACOSX")) {
                        entries.put(entry.getName(), readEntry(zip));
                    }
                } else {
                    zip.close();
                    return entries;
                }
            } catch (Throwable th) {
                try {
                    zip.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        }
    }

    private static byte[] firstEntry(Map<String, byte[]> entries, String... suffixes) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.US);
            for (String suffix : suffixes) {
                if (lower.endsWith(suffix)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static byte[] findModelJson(Map<String, byte[]> entries) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.US);
            if (lower.endsWith("/model.json") || lower.equals("model.json")) {
                return entry.getValue();
            }
        }
        for (Map.Entry<String, byte[]> entry2 : entries.entrySet()) {
            String lower2 = entry2.getKey().toLowerCase(Locale.US);
            if (lower2.endsWith(".json") || isEncryptedJsonName(lower2)) {
                if (jsonLooksLikeModel(entry2.getValue())) {
                    return entry2.getValue();
                }
            }
        }
        for (Map.Entry<String, byte[]> entry3 : entries.entrySet()) {
            String lower3 = entry3.getKey().toLowerCase(Locale.US);
            if (lower3.endsWith(".json") || isEncryptedJsonName(lower3)) {
                return entry3.getValue();
            }
        }
        return null;
    }

    private static String modelJsonName(Map<String, byte[]> entries, byte[] jsonBytes) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getValue() == jsonBytes) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static boolean jsonLooksLikeModel(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        return text.contains("\"bones\"") || text.contains("\"minecraft:geometry\"") || text.contains("\"geometry.");
    }

    private static boolean looksInternationalDuowan(Map<String, byte[]> entries) {
        boolean hasInfo = false;
        boolean hasModel = false;
        boolean hasSkin = false;
        Iterator<String> it = entries.keySet().iterator();
        while (true) {
            boolean z = true;
            if (!it.hasNext()) {
                break;
            }
            String name = it.next();
            String lower = name.toLowerCase(Locale.US);
            hasInfo = hasInfo || lower.endsWith("/info.json") || lower.equals("info.json");
            hasModel = hasModel || lower.endsWith("/model.json") || lower.equals("model.json");
            if (!hasSkin && !lower.endsWith("/skin.png") && !lower.equals("skin.png")) {
                z = false;
            }
            hasSkin = z;
        }
        return hasInfo && hasModel && hasSkin;
    }

    private static JsonDecodeResult decodeModelJson(Context context, byte[] jsonBytes, byte[] blurBytes) throws IOException {
        if (looksPlainJson(jsonBytes)) {
            return new JsonDecodeResult(jsonBytes, false);
        }
        if (startsWithMagic(jsonBytes, 0)) {
            return new JsonDecodeResult(decryptHeaderFile(context, jsonBytes, 0), true);
        }
        if (blurBytes != null) {
            return new JsonDecodeResult(decodeSkinJson(context, jsonBytes, blurBytes), true);
        }
        return new JsonDecodeResult(jsonBytes, false);
    }

    private static byte[] decodeSkinJson(Context context, byte[] jsonBytes, byte[] blurBytes) throws IOException {
        byte[] key = keyFromHeader(context, blurBytes);
        String text = new String(jsonBytes, StandardCharsets.UTF_8).trim();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[] chunks = text.contains("@") ? text.split("@") : new String[]{text};
        for (String chunk : chunks) {
            if (chunk.length() != 0) {
                byte[] bytes = chunk.getBytes(StandardCharsets.UTF_8);
                byte[] plain = key != null ? LauncherMiscUtil.decryptFileDataUsekey(bytes, key) : LauncherMiscUtil.decryptFileData(bytes);
                if (plain == null) {
                    throw error(context, R.string.error_decrypt_json);
                }
                out.write(plain);
            }
        }
        return out.toByteArray();
    }

    private static byte[] decodeMaybeEncryptedImage(Context context, byte[] data) throws IOException {
        int offset = encryptedImageOffset(data);
        if (offset >= 0) {
            return decryptHeaderFile(context, data, offset);
        }
        return data;
    }

    private static int encryptedImageOffset(byte[] data) {
        if (startsWithMagic(data, 0)) {
            return 0;
        }
        if (startsWithMagic(data, IMAGE_PAYLOAD_OFFSET)) {
            return IMAGE_PAYLOAD_OFFSET;
        }
        int pngEnd = pngEndOffset(data);
        if (pngEnd >= 0 && startsWithMagic(data, pngEnd)) {
            return pngEnd;
        }
        for (int i = 0; i <= (data.length - MAGIC.length) - 2; i++) {
            if (startsWithMagic(data, i)) {
                return i;
            }
        }
        return -1;
    }

    private static int pngEndOffset(byte[] data) {
        int length;
        if (!isZip(data) && data.length >= 8 && (data[0] & 255) == 137 && data[1] == 80 && data[2] == 78 && data[3] == 71) {
            int offset = 8;
            while (offset + 12 <= data.length && (length = ((data[offset] & 255) << 24) | ((data[offset + 1] & 255) << 16) | ((data[offset + 2] & 255) << 8) | (data[offset + 3] & 255)) >= 0 && offset + 12 + length <= data.length) {
                String type = new String(data, offset + 4, 4, StandardCharsets.US_ASCII);
                offset += length + 12;
                if ("IEND".equals(type)) {
                    return offset;
                }
            }
            return -1;
        }
        return -1;
    }

    private static byte[] decryptHeaderFile(Context context, byte[] data, int offset) throws IOException {
        int headerLen = ((data[MAGIC.length + offset] & 255) << 8) | (data[MAGIC.length + offset + 1] & 255);
        int bodyOffset = MAGIC.length + offset + 2 + headerLen;
        if (bodyOffset > data.length) {
            throw error(context, R.string.error_bad_header);
        }
        byte[] header = LauncherMiscUtil.decryptFileData(Arrays.copyOfRange(data, MAGIC.length + offset + 2, bodyOffset));
        if (header == null) {
            throw error(context, R.string.error_decrypt_header);
        }
        byte[] body = Arrays.copyOfRange(data, bodyOffset, data.length);
        if (body.length == 0) {
            return header;
        }
        if (header.length >= 24 && header[7] != 0) {
            byte[] plain = LauncherMiscUtil.decryptFileDataUsekey(trimAscii(body), Arrays.copyOfRange(header, 8, 24));
            if (plain == null) {
                throw error(context, R.string.error_decrypt_body);
            }
            return plain;
        }
        byte[] plain2 = LauncherMiscUtil.decryptFileData(trimAscii(body));
        if (plain2 == null) {
            throw error(context, R.string.error_decrypt_body);
        }
        return plain2;
    }

    private static byte[] keyFromHeader(Context context, byte[] data) throws IOException {
        if (!startsWithMagic(data, 0)) {
            return null;
        }
        int headerLen = ((data[MAGIC.length] & 255) << 8) | (data[MAGIC.length + 1] & 255);
        int headerEnd = MAGIC.length + 2 + headerLen;
        if (headerEnd > data.length) {
            throw error(context, R.string.error_bad_blur);
        }
        byte[] header = LauncherMiscUtil.decryptFileData(Arrays.copyOfRange(data, MAGIC.length + 2, headerEnd));
        if (header == null) {
            throw error(context, R.string.error_decrypt_blur);
        }
        if (header.length < 24 || header[7] == 0) {
            return null;
        }
        return Arrays.copyOfRange(header, 8, 24);
    }

    private static List<SkinPreviewData.Part> parseModel(Context context, String json, int textureHeight) throws Exception {
        JSONObject root;
        JSONObject geometry;
        JSONArray bones;
        JSONObject geometry2;
        JSONArray bones2;
        JSONObject bone;
        String name;
        String parent;
        JSONObject root2 = new JSONObject(json);
        JSONObject geometry3 = chooseGeometry(context, root2, textureHeight);
        JSONArray bones3 = geometry3.getJSONArray("bones");
        ArrayList<SkinPreviewData.Part> parts = new ArrayList<>();
        int i = 0;
        while (i < bones3.length()) {
            JSONObject bone2 = bones3.getJSONObject(i);
            if (bone2.optBoolean("neverRender", false)) {
                root = root2;
                geometry = geometry3;
                bones = bones3;
            } else if (!bone2.has("cubes")) {
                root = root2;
                geometry = geometry3;
                bones = bones3;
            } else {
                String name2 = bone2.optString("name", "");
                String parent2 = bone2.optString("parent", bone2.optString("material", ""));
                boolean mirror = bone2.optBoolean("mirror", false);
                root = root2;
                SkinPreviewData.Part part = new SkinPreviewData.Part(name2, parent2, floatArray(bone2.optJSONArray("pivot"), 0.0f, 0.0f, 0.0f));
                JSONArray cubes = bone2.getJSONArray("cubes");
                int j = 0;
                while (j < cubes.length()) {
                    JSONObject cube = cubes.getJSONObject(j);
                    int[] uv = intArray(cube.getJSONArray("uv"), 0, 0);
                    if (uv[0] < 0) {
                        geometry2 = geometry3;
                        bones2 = bones3;
                        bone = bone2;
                        name = name2;
                        parent = parent2;
                    } else if (uv[1] < 0) {
                        geometry2 = geometry3;
                        bones2 = bones3;
                        bone = bone2;
                        name = name2;
                        parent = parent2;
                    } else {
                        geometry2 = geometry3;
                        bones2 = bones3;
                        bone = bone2;
                        name = name2;
                        parent = parent2;
                        part.cubes.add(new SkinPreviewData.Cube(floatArray(cube.getJSONArray("origin"), 0.0f, 0.0f, 0.0f), floatArray(cube.getJSONArray("size"), 1.0f, 1.0f, 1.0f), uv, (float) cube.optDouble("inflate", 0.0d), cube.optBoolean("mirror", mirror)));
                    }
                    j++;
                    name2 = name;
                    geometry3 = geometry2;
                    bones3 = bones2;
                    bone2 = bone;
                    parent2 = parent;
                }
                geometry = geometry3;
                bones = bones3;
                parts.add(part);
            }
            i++;
            root2 = root;
            geometry3 = geometry;
            bones3 = bones;
        }
        if (parts.isEmpty()) {
            return defaultSteveModel(textureHeight <= 32);
        }
        return parts;
    }

    private static JSONObject chooseGeometry(Context context, JSONObject root, int textureHeight) throws Exception {
        String preferred = textureHeight == 32 ? "geometry.humanoid" : "geometry.humanoid.custom";
        if (root.has(preferred)) {
            return root.getJSONObject(preferred);
        }
        if (root.has("minecraft:geometry")) {
            JSONArray geometries = root.getJSONArray("minecraft:geometry");
            if (geometries.length() > 0) {
                return geometries.getJSONObject(0);
            }
        }
        String fallback = null;
        JSONArray names = root.names();
        if (names == null) {
            throw error(context, R.string.error_no_geometry);
        }
        for (int i = 0; i < names.length(); i++) {
            String name = names.getString(i);
            if (name.startsWith("geometry.") && root.getJSONObject(name).has("bones")) {
                fallback = name;
            }
        }
        if (fallback == null) {
            throw error(context, R.string.error_no_geometry);
        }
        return root.getJSONObject(fallback);
    }

    static List<SkinPreviewData.Part> standardModel(int textureHeight, boolean slimArms) {
        boolean oldSkin = textureHeight <= 32;
        ArrayList<SkinPreviewData.Part> parts = new ArrayList<>();
        addBox(parts, "head", "", new float[]{0.0f, 24.0f, 0.0f}, new float[]{-4.0f, 24.0f, -4.0f}, new float[]{8.0f, 8.0f, 8.0f}, new int[]{0, 0});
        addBox(parts, "body", "", new float[]{0.0f, 24.0f, 0.0f}, new float[]{-4.0f, 12.0f, -2.0f}, new float[]{8.0f, 12.0f, 4.0f}, new int[]{16, 16});
        addBox(parts, "rightArm", "", new float[]{-5.0f, 22.0f, 0.0f}, new float[]{slimArms ? -7.0f : -8.0f, 12.0f, -2.0f}, new float[]{slimArms ? 3.0f : 4.0f, 12.0f, 4.0f}, new int[]{40, 16});
        float[] fArr = {5.0f, 22.0f, 0.0f};
        float[] fArr2 = {4.0f, 12.0f, -2.0f};
        float[] fArr3 = {slimArms ? 3.0f : 4.0f, 12.0f, 4.0f};
        int[] iArr = new int[2];
        if (oldSkin) {
            iArr[0] = 40;
            iArr[1] = 16;
        } else {
            iArr[0] = 32;
            iArr[1] = 48;
        }
        addBox(parts, "leftArm", "", fArr, fArr2, fArr3, iArr);
        addBox(parts, "rightLeg", "", new float[]{-2.0f, 12.0f, 0.0f}, new float[]{-4.0f, 0.0f, -2.0f}, new float[]{4.0f, 12.0f, 4.0f}, new int[]{0, 16});
        float[] fArr4 = {2.0f, 12.0f, 0.0f};
        float[] fArr5 = {0.0f, 0.0f, -2.0f};
        float[] fArr6 = {4.0f, 12.0f, 4.0f};
        int[] iArr2 = new int[2];
        if (oldSkin) {
            iArr2[0] = 0;
            iArr2[1] = 16;
        } else {
            iArr2[0] = 16;
            iArr2[1] = 48;
        }
        addBox(parts, "leftLeg", "", fArr4, fArr5, fArr6, iArr2);
        addBox(parts, "head", "", new float[]{0.0f, 24.0f, 0.0f}, new float[]{-4.0f, 24.0f, -4.0f}, new float[]{8.0f, 8.0f, 8.0f}, new int[]{32, 0}, 0.5f);
        if (!oldSkin) {
            addBox(parts, "body", "", new float[]{0.0f, 24.0f, 0.0f}, new float[]{-4.0f, 12.0f, -2.0f}, new float[]{8.0f, 12.0f, 4.0f}, new int[]{16, 32}, 0.25f);
            addBox(parts, "rightArm", "", new float[]{-5.0f, 22.0f, 0.0f}, new float[]{slimArms ? -7.0f : -8.0f, 12.0f, -2.0f}, new float[]{slimArms ? 3.0f : 4.0f, 12.0f, 4.0f}, new int[]{40, 32}, 0.25f);
            addBox(parts, "leftArm", "", new float[]{5.0f, 22.0f, 0.0f}, new float[]{4.0f, 12.0f, -2.0f}, new float[]{slimArms ? 3.0f : 4.0f, 12.0f, 4.0f}, new int[]{48, 48}, 0.25f);
            addBox(parts, "rightLeg", "", new float[]{-2.0f, 12.0f, 0.0f}, new float[]{-4.0f, 0.0f, -2.0f}, new float[]{4.0f, 12.0f, 4.0f}, new int[]{0, 32}, 0.25f);
            addBox(parts, "leftLeg", "", new float[]{2.0f, 12.0f, 0.0f}, new float[]{0.0f, 0.0f, -2.0f}, new float[]{4.0f, 12.0f, 4.0f}, new int[]{0, 48}, 0.25f);
        }
        return parts;
    }

    private static List<SkinPreviewData.Part> defaultSteveModel(boolean oldSkin) {
        return standardModel(oldSkin ? 32 : 64, false);
    }

    private static void addBox(List<SkinPreviewData.Part> parts, String name, String parent, float[] pivot, float[] origin, float[] size, int[] uv) {
        addBox(parts, name, parent, pivot, origin, size, uv, 0.0f);
    }

    private static void addBox(List<SkinPreviewData.Part> parts, String name, String parent, float[] pivot, float[] origin, float[] size, int[] uv, float inflate) {
        SkinPreviewData.Part part = new SkinPreviewData.Part(name, parent, pivot);
        part.cubes.add(new SkinPreviewData.Cube(origin, size, uv, inflate, false));
        parts.add(part);
    }

    private static float[] floatArray(JSONArray array, float d0, float d1, float d2) throws Exception {
        if (array != null && array.length() >= 3) {
            return new float[]{(float) array.getDouble(0), (float) array.getDouble(1), (float) array.getDouble(2)};
        }
        return new float[]{d0, d1, d2};
    }

    private static int[] intArray(JSONArray array, int d0, int d1) throws Exception {
        if (array == null || array.length() < 2) {
            return new int[]{d0, d1};
        }
        return new int[]{array.getInt(0), array.getInt(1)};
    }

    private static boolean isZip(byte[] data) {
        return data.length >= 4 && data[0] == 80 && data[1] == 75;
    }

    private static boolean isEncryptedJsonName(String lower) {
        return lower.endsWith("-mc") || lower.endsWith(".json-mc") || lower.endsWith("-mc.json");
    }

    private static boolean looksPlainJson(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8).trim();
        return text.startsWith("{") || text.startsWith("[");
    }

    private static boolean startsWithMagic(byte[] data, int offset) {
        if (offset < 0 || data.length < MAGIC.length + offset + 2) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[offset + i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] trimAscii(byte[] data) {
        int start = 0;
        int end = data.length;
        while (start < end && data[start] <= 32) {
            start++;
        }
        while (end > start && data[end - 1] <= 32) {
            end--;
        }
        return (start == 0 && end == data.length) ? data : Arrays.copyOfRange(data, start, end);
    }

    /* JADX WARN: Removed duplicated region for block: B:33:0x0033 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private static byte[] readAll(Context context, InputStream in) throws IOException {
        if (in == null) {
            throw error(context, R.string.error_open_input);
        }
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static byte[] readEntry(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        while (true) {
            int read = in.read(buffer);
            if (read != -1) {
                out.write(buffer, 0, read);
            } else {
                return out.toByteArray();
            }
        }
    }

    private static IOException error(Context context, int messageRes) {
        return new IOException(context.getString(messageRes));
    }

    private static final class JsonDecodeResult {
        final byte[] data;
        final boolean encrypted;

        JsonDecodeResult(byte[] data, boolean encrypted) {
            this.data = data;
            this.encrypted = encrypted;
        }
    }
}
