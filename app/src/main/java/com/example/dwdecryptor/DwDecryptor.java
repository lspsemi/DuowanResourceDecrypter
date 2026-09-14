package com.example.dwdecryptor;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import androidx.documentfile.provider.DocumentFile;

import com.litl.leveldb.DB;
import com.mcbox.pesdk.util.LauncherMiscUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.json.JSONObject;

public final class DwDecryptor {
    public interface Logger {
        void log(String message);
    }

    public enum MapDbRepairMode {
        DELETE_MVILLAGES,
        DECRYPT_MVILLAGES
    }

    private static final byte[] MAGIC = "19311931EE".getBytes(StandardCharsets.US_ASCII);
    private static final int IMAGE_PAYLOAD_OFFSET = 0x546;
    private static final String OUT_PREFIX = "_dw_decrypted_";

    private final Context context;
    private final Logger logger;
    private final boolean repairMapDb;
    private final MapDbRepairMode mapDbRepairMode;
    private final boolean keepRepairArtifacts;
    private final boolean analyzeMapWal;
    private final boolean analyzeUserKey;
    private final boolean chineseLogs;
    private int decrypted;
    private int copied;
    private int skipped;
    private int failed;
    private int repaired;
    private int zipEntryDecrypted;
    private int zipEntryFailed;
    private int zipEntryMissingKey;
    private final List<String> successfulResources = new ArrayList<>();
    private final List<String> failedResources = new ArrayList<>();

    public DwDecryptor(Context context, Logger logger) {
        this(context, logger, false, false);
    }

    public DwDecryptor(Context context, Logger logger, boolean repairMapDb) {
        this(context, logger, repairMapDb, false);
    }

    public DwDecryptor(Context context, Logger logger, boolean repairMapDb, boolean keepRepairArtifacts) {
        this(context, logger, repairMapDb, keepRepairArtifacts, false);
    }

    public DwDecryptor(Context context, Logger logger, boolean repairMapDb,
                       boolean keepRepairArtifacts, boolean analyzeMapWal) {
        this(context, logger, repairMapDb, MapDbRepairMode.DELETE_MVILLAGES,
                keepRepairArtifacts, analyzeMapWal);
    }

    public DwDecryptor(Context context, Logger logger, boolean repairMapDb,
                       MapDbRepairMode mapDbRepairMode,
                       boolean keepRepairArtifacts, boolean analyzeMapWal) {
        this(context, logger, repairMapDb, mapDbRepairMode, keepRepairArtifacts, analyzeMapWal, false);
    }

    public DwDecryptor(Context context, Logger logger, boolean repairMapDb,
                       MapDbRepairMode mapDbRepairMode,
                       boolean keepRepairArtifacts, boolean analyzeMapWal,
                       boolean analyzeUserKey) {
        this(context, logger, repairMapDb, mapDbRepairMode, keepRepairArtifacts,
                analyzeMapWal, analyzeUserKey, false);
    }

    public DwDecryptor(Context context, Logger logger, boolean repairMapDb,
                       MapDbRepairMode mapDbRepairMode,
                       boolean keepRepairArtifacts, boolean analyzeMapWal,
                       boolean analyzeUserKey, boolean chineseLogs) {
        this.context = context.getApplicationContext();
        this.logger = logger;
        this.repairMapDb = repairMapDb;
        this.mapDbRepairMode = mapDbRepairMode == null
                ? MapDbRepairMode.DELETE_MVILLAGES : mapDbRepairMode;
        this.keepRepairArtifacts = keepRepairArtifacts;
        this.analyzeMapWal = analyzeMapWal;
        this.analyzeUserKey = analyzeUserKey;
        this.chineseLogs = chineseLogs;
    }

    public void decryptTree(Uri treeUri) throws IOException {
        DocumentFile input = DocumentFile.fromTreeUri(context, treeUri);
        if (input == null || !input.isDirectory()) {
            throw new IOException("Invalid input directory");
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        DocumentFile output = input.createDirectory(OUT_PREFIX + stamp);
        if (output == null) {
            throw new IOException("Cannot create output directory");
        }

        log("Output: " + output.getName());
        log("Map DB repair: " + (repairMapDb ? "on" : "off"));
        log("Map DB repair mode: " + mapDbRepairModeLabel());
        log("Keep repair artifacts: " + (keepRepairArtifacts ? "on" : "off"));
        log("Analyze map log decryption: " + (analyzeMapWal ? "on" : "off"));
        log("Analyze user key: " + (analyzeUserKey ? "on" : "off"));
        decryptDirectory(input, output, null);
        log("Done. filesDecryptedOrRepacked=" + decrypted + ", repaired=" + repaired
                + ", copied=" + copied + ", skipped=" + skipped + ", fileFailed=" + failed
                + ", zipEntriesDecrypted=" + zipEntryDecrypted
                + ", zipEntriesFailed=" + zipEntryFailed
                + ", zipEntriesMissingKey=" + zipEntryMissingKey);
        logResourceSummary();
    }

    private void decryptDirectory(DocumentFile input, DocumentFile output, byte[] inheritedKey) {
        byte[] localKey = firstKeyIn(input);
        byte[] activeKey = localKey != null ? localKey : inheritedKey;

        DocumentFile[] children = input.listFiles();
        for (DocumentFile child : children) {
            String name = child.getName();
            if (name == null) {
                continue;
            }
            if (isAnalysisArtifactName(name.toLowerCase(Locale.US))) {
                skipped++;
                continue;
            }
            if (child.isDirectory()) {
                if (name.startsWith(OUT_PREFIX)) {
                    skipped++;
                    continue;
                }
                DocumentFile outDir = output.createDirectory(name);
                if (outDir != null) {
                    if (repairMapDb && "db".equalsIgnoreCase(name)) {
                        try {
                            if (analyzeMapWal) {
                                analyzeDbDirectoryLogs(child, outDir, activeKey, "db");
                            }
                            if (repairDbDirectory(child, outDir, "db", activeKey)) {
                                continue;
                            }
                        } catch (Throwable t) {
                            failed++;
                            log("db repair failed " + name + ": " + t.getMessage());
                        }
                    }
                    decryptDirectory(child, outDir, activeKey);
                }
                continue;
            }

            try {
                processFile(child, input, output, activeKey, hasSibling(input, name + "-mc")
                        || hasSibling(input, name + "-mc.json")
                        || hasSibling(input, name + "-mc"));
            } catch (Exception e) {
                failed++;
                recordFailedResource(name + " (" + e.getMessage() + ")");
                log("FAIL " + name + ": " + e.getMessage());
            }
        }
    }

    private void processFile(DocumentFile file, DocumentFile inputDir, DocumentFile outputDir,
                             byte[] key, boolean hasEncryptedSibling) throws IOException {
        String name = file.getName();
        if (name == null) {
            return;
        }
        String lower = name.toLowerCase(Locale.US);

        if (analyzeUserKey && ".user.key".equals(lower)) {
            byte[] levelKeyData = readOptionalFile(inputDir, ".level.key");
            writeUserKeyAnalysis(outputDir, name, readAll(file), levelKeyData);
        }
        if (isMetadataName(lower)) {
            skipped++;
            return;
        }

        byte[] data = readAll(file);
        if (analyzeMapWal && lower.endsWith(".log") && isSuspiciousLevelDbWal(data)) {
            writeWalAnalysis(file, outputDir, name, data, key, false);
        }
        if (repairMapDb && lower.endsWith(".log") && isSuspiciousLevelDbWal(data)) {
            if (keepRepairArtifacts) {
                writeFile(outputDir, name + ".dw_injected_wal.bak", "application/octet-stream", data);
            }
            repaired++;
            log("repair wal skip " + name);
            return;
        }
        if (repairMapDb && lower.endsWith(".ldb") && containsSuspiciousLevelDbValue(data)) {
            if (keepRepairArtifacts) {
                byte[] slice = extractSuspiciousLevelDbSlice(data);
                if (slice.length > 0) {
                    writeFile(outputDir, name + ".dw_mVillages_slice.bin", "application/octet-stream", slice);
                }
            }
            log("warn ldb may contain merged mVillages marker: " + name);
        }
        if (isZipLike(lower)) {
            int failedBefore = zipEntryFailed;
            int missingKeyBefore = zipEntryMissingKey;
            byte[] zip = decryptZipContainer(data, lower.endsWith(".modpkg"));
            writeFile(outputDir, name, containerMimeFor(name), zip);
            decrypted++;
            int entryFailures = zipEntryFailed - failedBefore;
            int missingKeys = zipEntryMissingKey - missingKeyBefore;
            if (entryFailures > 0) {
                recordFailedResource(name + " (partial, zipEntriesFailed=" + entryFailures
                        + ", missingKey=" + missingKeys + ")");
                log("zip partial " + name + " entriesFailed=" + entryFailures + " missingKey=" + missingKeys);
            } else {
                recordSuccessfulResource(name);
            }
            log("zip " + name);
            System.gc();
            return;
        }

        if (startsWithMagic(data)) {
            byte[] plain = decryptHeaderFile(data);
            writeFile(outputDir, name, mimeFor(name), plain);
            decrypted++;
            recordSuccessfulResource(name);
            log("header " + name);
            return;
        }

        if (startsWithMagicAt(data, IMAGE_PAYLOAD_OFFSET)) {
            byte[] plain = decryptEmbeddedHeaderFile(data, IMAGE_PAYLOAD_OFFSET);
            writeFile(outputDir, name, mimeFor(name), plain);
            decrypted++;
            recordSuccessfulResource(name);
            log("embedded image " + name + " " + data.length + " -> " + plain.length);
            return;
        }

        if (isMcEncryptedName(lower)) {
            if (key == null) {
                writeFile(outputDir, name, mimeFor(name), data);
                failed++;
                recordFailedResource(name + " (missing key/header)");
                log("missing key/header for encrypted file " + name);
                return;
            }
            byte[] plain = decryptMapData(data, key);
            writeFile(outputDir, stripMcSuffix(name), mimeFor(stripMcSuffix(name)), plain);
            decrypted++;
            recordSuccessfulResource(name);
            log("json-mc " + name);
            return;
        }

        if (hasEncryptedSibling && isLikelyPlaceholderJson(lower, data)) {
            skipped++;
            return;
        }

        writeFile(outputDir, name, mimeFor(name), data);
        copied++;
        recordSuccessfulResource(name + " (copied/plain)");
    }

    private byte[] decryptZipContainer(byte[] data) throws IOException {
        try {
            return decryptZipContainer(data, StandardCharsets.UTF_8, false);
        } catch (IOException utf8Error) {
            log("zip utf8 failed, retry gbk: " + utf8Error.getMessage());
            return decryptZipContainer(data, Charset.forName("GBK"), false);
        }
    }

    private byte[] decryptZipContainer(byte[] data, boolean modpkgContainer) throws IOException {
        try {
            return decryptZipContainer(data, StandardCharsets.UTF_8, modpkgContainer);
        } catch (IOException utf8Error) {
            log("zip utf8 failed, retry gbk: " + utf8Error.getMessage());
            return decryptZipContainer(data, Charset.forName("GBK"), modpkgContainer);
        }
    }

    private byte[] decryptZipContainer(byte[] data, Charset charset, boolean modpkgContainer) throws IOException {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        List<ZipItem> orderedEntries = readZipEntries(data, charset);
        Map<String, byte[]> entries = new HashMap<>();
        for (ZipItem item : orderedEntries) {
            if (!item.directory) {
                entries.put(item.name, item.data);
            }
        }
        ZipKeySet zipKeys = keysFromZipEntries(entries);
        Set<String> written = new HashSet<>();
        Map<String, byte[]> zipAnalysisArtifacts = new HashMap<>();
        ZipDbRepairPlan dbRepairPlan = repairZipDatabases(orderedEntries, zipKeys);
        try (ZipOutputStream zout = new ZipOutputStream(outBytes)) {
            List<ZipOutItem> outputItems = new ArrayList<>();
            Set<String> decryptedOutputNames = new HashSet<>();
            for (ZipItem item : orderedEntries) {
                String entryName = item.name;
                String lower = entryName.toLowerCase(Locale.US);
                if (!item.directory && analyzeUserKey
                        && (lower.equals(".user.key") || lower.endsWith("/.user.key"))) {
                    String levelKeyName = parentPath(entryName) + ".level.key";
                    byte[] levelKeyData = entries.get(levelKeyName);
                    String report = analyzeUserKey(entryName, item.data, levelKeyData);
                    zipAnalysisArtifacts.put(entryName + ".dw_user_key_analysis.txt",
                            report.getBytes(StandardCharsets.UTF_8));
                    log("zip user key analysis " + entryName);
                }
                if (!item.directory && isMetadataPath(lower)) {
                    log("zip skip metadata " + entryName);
                    continue;
                }
                if (!item.directory && analyzeMapWal && isInDbDir(lower)
                        && lower.endsWith(".log") && isSuspiciousLevelDbWal(item.data)) {
                    WalAnalysisBundle bundle = analyzeWalData(entryName, item.data,
                            zipKeys == null ? null : zipKeys.firstKeyFor(entryName));
                    String base = entryName + ".dw_wal_analysis";
                    zipAnalysisArtifacts.put(base + ".txt", bundle.report);
                    zipAnalysisArtifacts.putAll(prefixArtifacts(base + ".", bundle.binaries));
                    log("zip wal analysis " + entryName);
                }
                if (!item.directory && repairMapDb && isInDbDir(lower)
                        && lower.endsWith(".log") && isSuspiciousLevelDbWal(item.data)) {
                    if (keepRepairArtifacts) {
                        String artifactName = entryName + ".dw_injected_wal.bak";
                        ZipEntry artifactEntry = new ZipEntry(artifactName);
                        zout.putNextEntry(artifactEntry);
                        zout.write(item.data);
                        zout.closeEntry();
                        written.add(artifactName);
                        log("zip keep wal artifact " + artifactName);
                    }
                    repaired++;
                    log("zip repair wal skip " + entryName);
                    continue;
                }
                if (!item.directory && dbRepairPlan.replacements.containsKey(entryName)) {
                    outputItems.add(new ZipOutItem(entryName, lower, entryName, false,
                            dbRepairPlan.replacements.get(entryName), false));
                    continue;
                }
                byte[] entryData = item.data;
                boolean decryptedEntry = false;
                if (!item.directory) {
                    try {
                        byte[] transformed = maybeDecryptEntry(entryName, entryData, zipKeys, modpkgContainer);
                        decryptedEntry = transformed != entryData;
                        entryData = transformed;
                        if (decryptedEntry) {
                            zipEntryDecrypted++;
                        }
                    } catch (Throwable t) {
                        zipEntryFailed++;
                        if (t instanceof MissingZipKeyException) {
                            zipEntryMissingKey++;
                        }
                        log("zip entry fail " + entryName + ": " + t.getMessage());
                    }
                }
                String outName = outputNameForEntry(entryName, lower, entryData, decryptedEntry, item.directory);
                outputItems.add(new ZipOutItem(entryName, lower, outName, item.directory, entryData, decryptedEntry));
                if (decryptedEntry && !item.directory) {
                    decryptedOutputNames.add(outName);
                }
            }
            for (ZipOutItem item : outputItems) {
                if (!item.directory && item.originalLower.endsWith(".json")
                        && hasEncryptedZipSibling(entries, item.originalName)
                        && decryptedOutputNames.contains(item.outName)) {
                    log("zip skip placeholder " + item.originalName);
                    continue;
                }
                if (written.contains(item.outName)) {
                    log("zip skip duplicate " + item.originalName + " -> " + item.outName);
                    continue;
                }
                written.add(item.outName);
                ZipEntry outEntry = new ZipEntry(item.directory && !item.outName.endsWith("/") ? item.outName + "/" : item.outName);
                zout.putNextEntry(outEntry);
                zout.write(item.data);
                zout.closeEntry();
            }
            for (Map.Entry<String, byte[]> replacement : dbRepairPlan.replacements.entrySet()) {
                String entryName = replacement.getKey();
                if (written.contains(entryName)) {
                    continue;
                }
                ZipEntry outEntry = new ZipEntry(entryName);
                zout.putNextEntry(outEntry);
                zout.write(replacement.getValue());
                zout.closeEntry();
                written.add(entryName);
                log("zip add repaired db file " + entryName);
            }
            if (keepRepairArtifacts || analyzeMapWal) {
                for (Map.Entry<String, byte[]> artifact : dbRepairPlan.artifacts.entrySet()) {
                    String entryName = artifact.getKey();
                    if (written.contains(entryName)) {
                        continue;
                    }
                    ZipEntry outEntry = new ZipEntry(entryName);
                    zout.putNextEntry(outEntry);
                    zout.write(artifact.getValue());
                    zout.closeEntry();
                    written.add(entryName);
                    log("zip keep db artifact " + entryName);
                }
            }
            for (Map.Entry<String, byte[]> artifact : zipAnalysisArtifacts.entrySet()) {
                String entryName = artifact.getKey();
                if (written.contains(entryName)) {
                    continue;
                }
                ZipEntry outEntry = new ZipEntry(entryName);
                zout.putNextEntry(outEntry);
                zout.write(artifact.getValue());
                zout.closeEntry();
                written.add(entryName);
                log("zip add wal analysis " + entryName);
            }
        }
        byte[] result = outBytes.toByteArray();
        logZipResiduals(result, charset);
        return result;
    }

    private void logZipResiduals(byte[] zipData, Charset charset) {
        try {
            List<ZipItem> items = readZipEntries(zipData, charset);
            Set<String> names = new HashSet<>();
            for (ZipItem item : items) {
                names.add(item.name);
            }
            for (ZipItem item : items) {
                if (item.directory) {
                    continue;
                }
                String lower = item.name.toLowerCase(Locale.US);
                if (isMetadataPath(lower) || isMcEncryptedName(lower)) {
                    log("residual name " + item.name);
                } else if (startsWithMagic(item.data) || startsWithMagicAt(item.data, IMAGE_PAYLOAD_OFFSET)) {
                    log("residual magic " + item.name);
                } else if (isAddonOrResourceJson(lower) && looksLikeEncryptedText(item.data)) {
                    log("residual encrypted json " + item.name);
                } else if (isInDbDir(lower) && containsSuspiciousLevelDbValue(item.data)) {
                    log("residual db marker " + item.name);
                }
                if (lower.endsWith("/db/current") || lower.endsWith("\\db\\current")) {
                    String manifest = new String(item.data, StandardCharsets.US_ASCII).trim();
                    String target = parentPath(item.name) + manifest;
                    if (manifest.startsWith("MANIFEST-") && !names.contains(target)) {
                        log("residual db current missing " + target);
                    }
                }
            }
        } catch (Throwable t) {
            log("residual scan failed: " + t.getMessage());
        }
    }

    private void recordSuccessfulResource(String name) {
        successfulResources.add(name);
    }

    private void recordFailedResource(String name) {
        failedResources.add(name);
    }

    private void logResourceSummary() {
        log("Successful resources (" + successfulResources.size() + "):");
        if (successfulResources.isEmpty()) {
            log("  none");
        } else {
            for (String name : successfulResources) {
                log("  OK " + name);
            }
        }

        log("Failed or partial resources (" + failedResources.size() + "):");
        if (failedResources.isEmpty()) {
            log("  none");
        } else {
            for (String name : failedResources) {
                log("  FAIL " + name);
            }
        }
    }

    private ZipDbRepairPlan repairZipDatabases(List<ZipItem> orderedEntries, ZipKeySet zipKeys) {
        Map<String, List<ZipItem>> groups = new HashMap<>();
        for (ZipItem item : orderedEntries) {
            if (item.directory) {
                continue;
            }
            String root = dbRootPrefix(item.name);
            if (root == null) {
                continue;
            }
            List<ZipItem> group = groups.get(root);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(root, group);
            }
            group.add(item);
        }

        ZipDbRepairPlan plan = new ZipDbRepairPlan();
        for (Map.Entry<String, List<ZipItem>> group : groups.entrySet()) {
            try {
                File tempDb = createTempDirectory("dw_leveldb_zip_");
                for (ZipItem item : group.getValue()) {
                    String relative = item.name.substring(group.getKey().length());
                    writeLocalFile(new File(tempDb, relative), item.data);
                }
                byte[] groupKey = zipKeys == null ? null : zipKeys.firstKeyFor(group.getKey() + "mVillages");
                DbRepairResult result = repairLocalDb(tempDb, groupKey);
                if (result.changed) {
                    for (Map.Entry<String, byte[]> file : readLocalFiles(tempDb).entrySet()) {
                        plan.replacements.put(group.getKey() + file.getKey(), file.getValue());
                    }
                    if (keepRepairArtifacts && result.oldValue != null) {
                        plan.artifacts.put(group.getKey() + "mVillages.dw_merged_value.bak", result.oldValue);
                    }
                    if (analyzeMapWal && result.oldValue != null) {
                        WalAnalysisBundle bundle = analyzeEncryptedValue(
                                group.getKey() + "mVillages.merged", result.oldValue, groupKey);
                        plan.artifacts.put(group.getKey() + "mVillages.dw_merged_analysis.txt", bundle.report);
                        plan.artifacts.putAll(prefixArtifacts(group.getKey(), bundle.binaries));
                    }
                    log("zip repair merged db " + group.getKey() + "mVillages");
                }
                deleteLocalTree(tempDb);
            } catch (Throwable t) {
                log("zip merged db repair failed " + group.getKey() + ": " + t.getMessage());
            }
        }
        return plan;
    }

    private byte[] maybeDecryptEntry(String entryName, byte[] entryData, ZipKeySet zipKeys, boolean modpkgContainer) throws IOException {
        String lower = entryName.toLowerCase(Locale.US);
        if (repairMapDb && isInDbDir(lower) && lower.endsWith(".log") && isSuspiciousLevelDbWal(entryData)) {
            repaired++;
            log("zip repair wal empty " + entryName);
            return new byte[0];
        }
        if (repairMapDb && isInDbDir(lower) && lower.endsWith(".ldb") && containsSuspiciousLevelDbValue(entryData)) {
            log("zip warn ldb may contain merged mVillages marker: " + entryName);
        }
        if (modpkgContainer && isModpkgScriptLikeName(lower)) {
            byte[] modulePlain = tryDecryptModule(entryData);
            if (modulePlain != null && modulePlain.length > 0 && isLikelyText(modulePlain)) {
                if (entryData.length > 40960 || modulePlain.length > 40960) {
                    log("zip module script " + entryName + " " + entryData.length + " -> " + modulePlain.length);
                }
                return modulePlain;
            }
        }
        if (startsWithMagic(entryData)) {
            byte[] plain = decryptHeaderFile(entryData);
            if (isModpkgScriptLikeName(lower) && (entryData.length > 40960 || plain.length > 40960)) {
                log("zip header script " + entryName + " " + entryData.length + " -> " + plain.length);
            }
            return plain;
        }
        if (startsWithMagicAt(entryData, IMAGE_PAYLOAD_OFFSET)) {
            byte[] plain = decryptEmbeddedHeaderFile(entryData, IMAGE_PAYLOAD_OFFSET);
            if (plain.length != entryData.length) {
                log("zip embedded image " + entryName + " " + entryData.length + " -> " + plain.length);
            }
            return plain;
        }
        if (isAddonOrResourceJson(lower) && looksLikeEncryptedText(entryData)) {
            List<byte[]> keys = zipKeys == null ? Collections.<byte[]>emptyList() : zipKeys.keysFor(entryName);
            byte[] plain = decryptAddonJsonData(entryName, entryData, keys);
            log("zip addon json " + entryName + " " + entryData.length + " -> " + plain.length);
            return plain;
        }
        return entryData;
    }

    private List<ZipItem> readZipEntries(byte[] data, Charset charset) throws IOException {
        int eocd = findEndOfCentralDirectory(data);
        if (eocd < 0) {
            throw new IOException("zip end record not found");
        }
        int entryCount = u16(data, eocd + 10);
        int centralSize = u32(data, eocd + 12);
        int centralOffset = u32(data, eocd + 16);
        if (centralOffset < 0 || centralSize < 0 || centralOffset + centralSize > data.length) {
            throw new IOException("bad central directory");
        }

        List<ZipItem> entries = new ArrayList<>();
        int pos = centralOffset;
        for (int i = 0; i < entryCount; i++) {
            if (pos + 46 > data.length || u32(data, pos) != 0x02014b50) {
                throw new IOException("bad central entry");
            }
            int flags = u16(data, pos + 8);
            int method = u16(data, pos + 10);
            int compressedSize = u32(data, pos + 20);
            int nameLen = u16(data, pos + 28);
            int extraLen = u16(data, pos + 30);
            int commentLen = u16(data, pos + 32);
            int localOffset = u32(data, pos + 42);
            int nameStart = pos + 46;
            int next = nameStart + nameLen + extraLen + commentLen;
            if (nameLen < 0 || next > data.length) {
                throw new IOException("bad central name");
            }
            Charset nameCharset = (flags & 0x800) != 0 ? StandardCharsets.UTF_8 : charset;
            String name = decodeZipName(data, nameStart, nameLen, nameCharset);
            boolean directory = name.endsWith("/");
            byte[] entryData = directory ? new byte[0] : readLocalEntryData(data, localOffset, compressedSize, method);
            entries.add(new ZipItem(name, directory, entryData));
            pos = next;
        }
        return entries;
    }

    private static int findEndOfCentralDirectory(byte[] data) {
        int min = Math.max(0, data.length - 65557);
        for (int pos = data.length - 22; pos >= min; pos--) {
            if (u32(data, pos) == 0x06054b50) {
                return pos;
            }
        }
        return -1;
    }

    private static byte[] readLocalEntryData(byte[] data, int localOffset, int compressedSize, int method) throws IOException {
        if (localOffset < 0 || localOffset + 30 > data.length || u32(data, localOffset) != 0x04034b50) {
            throw new IOException("bad local entry");
        }
        int nameLen = u16(data, localOffset + 26);
        int extraLen = u16(data, localOffset + 28);
        int compressedStart = localOffset + 30 + nameLen + extraLen;
        int compressedEnd = compressedStart + compressedSize;
        if (compressedSize < 0 || compressedStart < 0 || compressedEnd > data.length) {
            throw new IOException("bad compressed data");
        }
        byte[] compressed = Arrays.copyOfRange(data, compressedStart, compressedEnd);
        if (method == 0) {
            return compressed;
        }
        if (method == 8) {
            Inflater inflater = new Inflater(true);
            try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed), inflater)) {
                return readAll(in);
            } finally {
                inflater.end();
            }
        }
        throw new IOException("unsupported zip method " + method);
    }

    private static String decodeZipName(byte[] data, int offset, int length, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(data, offset, length)).toString();
    }

    private static int u16(byte[] data, int offset) {
        if (offset < 0 || offset + 2 > data.length) {
            return -1;
        }
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static int u32(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) {
            return -1;
        }
        long value = (data[offset] & 0xffL)
                | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16)
                | ((data[offset + 3] & 0xffL) << 24);
        return value > Integer.MAX_VALUE ? -1 : (int) value;
    }

    private static final class ZipItem {
        final String name;
        final boolean directory;
        final byte[] data;

        ZipItem(String name, boolean directory, byte[] data) {
            this.name = name;
            this.directory = directory;
            this.data = data;
        }
    }

    private static final class ZipOutItem {
        final String originalName;
        final String originalLower;
        final String outName;
        final boolean directory;
        final byte[] data;
        final boolean decrypted;

        ZipOutItem(String originalName, String originalLower, String outName,
                   boolean directory, byte[] data, boolean decrypted) {
            this.originalName = originalName;
            this.originalLower = originalLower;
            this.outName = outName;
            this.directory = directory;
            this.data = data;
            this.decrypted = decrypted;
        }
    }

    private static final class MissingZipKeyException extends IllegalStateException {
        MissingZipKeyException(String message) {
            super(message);
        }
    }

    private static boolean hasEncryptedZipSibling(Map<String, byte[]> entries, String entryName) {
        return entries.containsKey(entryName + "-mc")
                || entries.containsKey(entryName.substring(0, Math.max(0, entryName.length() - ".json".length())) + "-mc.json")
                || entries.containsKey(entryName + "-mc.json");
    }

    private ZipKeySet keysFromZipEntries(Map<String, byte[]> entries) {
        ZipKeySet keys = new ZipKeySet();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.US);
            if (lower.endsWith("/.blur") || lower.endsWith("/.level.key")
                    || lower.endsWith("/.texture.key") || lower.equals(".blur")
                    || lower.equals(".level.key") || lower.equals(".texture.key")) {
                byte[] key = keyFromHeaderFile(entry.getValue());
                if (key != null) {
                    log("zip key " + entry.getKey());
                    keys.add(parentPath(entry.getKey()), key);
                }
            }
        }
        return keys.isEmpty() ? null : keys;
    }

    private byte[] decryptHeaderFile(byte[] data) throws IOException {
        if (data.length < MAGIC.length + 2 || !startsWithMagic(data)) {
            return data;
        }
        int headerLen = ((data[MAGIC.length] & 0xff) << 8) | (data[MAGIC.length + 1] & 0xff);
        int bodyOffset = MAGIC.length + 2 + headerLen;
        if (bodyOffset > data.length) {
            throw new IOException("bad encrypted header length");
        }

        byte[] encryptedHeader = Arrays.copyOfRange(data, MAGIC.length + 2, bodyOffset);
        byte[] header = LauncherMiscUtil.decryptFileData(encryptedHeader);
        if (header == null || header.length == 0) {
            throw new IOException("native returned empty header");
        }
        byte[] body = Arrays.copyOfRange(data, bodyOffset, data.length);
        if (body.length == 0) {
            return header;
        }
        if (header.length >= 24 && header[7] != 0) {
            return decryptHeaderBodyWithKey(body, Arrays.copyOfRange(header, 8, 24));
        }
        return decryptHeaderBodyWithoutKey(body);
    }

    private byte[] decryptEmbeddedHeaderFile(byte[] data, int offset) throws IOException {
        byte[] prefix = Arrays.copyOfRange(data, 0, offset);
        byte[] payload = Arrays.copyOfRange(data, offset, data.length);
        byte[] plainPayload = decryptHeaderFile(payload);
        if (isPng(prefix) && isPng(plainPayload)) {
            return plainPayload;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(prefix.length + plainPayload.length);
        out.write(prefix);
        out.write(plainPayload);
        return out.toByteArray();
    }

    private byte[] firstKeyIn(DocumentFile dir) {
        for (DocumentFile file : dir.listFiles()) {
            String name = file.getName();
            if (name == null || !file.isFile() || !isKeyMetadataName(name.toLowerCase(Locale.US))) {
                continue;
            }
            try {
                byte[] data = readAll(file);
                byte[] key = keyFromHeaderFile(data);
                if (key != null) {
                    log("key " + file.getName());
                    return key;
                }
            } catch (Exception e) {
                log("key read failed " + file.getName() + ": " + e.getMessage());
            }
        }
        return null;
    }

    private byte[] keyFromHeaderFile(byte[] data) {
        try {
            if (!startsWithMagic(data)) {
                return null;
            }
            int headerLen = ((data[MAGIC.length] & 0xff) << 8) | (data[MAGIC.length + 1] & 0xff);
            int headerEnd = MAGIC.length + 2 + headerLen;
            if (headerEnd > data.length) {
                return null;
            }
            byte[] header = LauncherMiscUtil.decryptFileData(Arrays.copyOfRange(data, MAGIC.length + 2, headerEnd));
            if (header.length >= 24 && header[7] != 0) {
                return Arrays.copyOfRange(header, 8, 24);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private byte[] decryptWithKey(byte[] data, byte[] key) {
        return LauncherMiscUtil.decryptFileDataUsekey(trimAscii(data), key);
    }

    private byte[] decryptHeaderBodyWithKey(byte[] body, byte[] key) throws IOException {
        byte[] input = trimAscii(body);
        if (looksLikeSegmentedEncryptedText(input)) {
            return decryptSegmentedWithKey(input, key);
        }
        return LauncherMiscUtil.decryptFileDataUsekey(input, key);
    }

    private byte[] decryptHeaderBodyWithoutKey(byte[] body) throws IOException {
        byte[] input = trimAscii(body);
        if (looksLikeSegmentedEncryptedText(input)) {
            return decryptSegmentedWithoutKey(input);
        }
        return LauncherMiscUtil.decryptFileData(input);
    }

    private static byte[] decryptSegmentedWithKey(byte[] input, byte[] key) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[] parts = new String(input, StandardCharsets.UTF_8).split("@");
        for (String part : parts) {
            byte[] chunk = LauncherMiscUtil.decryptFileDataUsekey(part.getBytes(StandardCharsets.UTF_8), key);
            if (chunk == null || chunk.length == 0) {
                throw new IOException("native returned empty segmented data");
            }
            out.write(chunk);
        }
        return out.toByteArray();
    }

    private static byte[] decryptSegmentedWithoutKey(byte[] input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[] parts = new String(input, StandardCharsets.UTF_8).split("@");
        for (String part : parts) {
            byte[] chunk = LauncherMiscUtil.decryptFileData(part.getBytes(StandardCharsets.UTF_8));
            if (chunk == null || chunk.length == 0) {
                throw new IOException("native returned empty segmented data");
            }
            out.write(chunk);
        }
        return out.toByteArray();
    }

    private byte[] tryDecryptModule(byte[] data) {
        try {
            return LauncherMiscUtil.decryptModule(data, data.length);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private byte[] decryptMapData(byte[] data, byte[] key) {
        return decryptMapData(data, key == null ? new ArrayList<byte[]>() : Arrays.asList(key));
    }

    private byte[] decryptMapData(byte[] data, List<byte[]> keys) {
        byte[] input = trimAscii(data);
        for (byte[] key : keys) {
            byte[] plain = decryptMapDataWithKey(input, key);
            if (plain != null && plain.length > 0) {
                return plain;
            }
        }
        byte[] plain = decryptMapDataWithoutKey(input);
        if (plain != null && plain.length > 0) {
            return plain;
        }
        throw new IllegalStateException("native returned empty data");
    }

    private byte[] decryptAddonJsonData(String entryName, byte[] data, List<byte[]> keys) {
        byte[] input = trimAscii(data);
        for (byte[] key : keys) {
            byte[] plain = decryptMapDataWithKey(input, key);
            if (isLikelyPlainJson(plain)) {
                return plain;
            }
        }

        byte[] plain = decryptMapDataWithoutKey(input);
        if (isLikelyPlainJson(plain)) {
            if (!keys.isEmpty()) {
                log("zip addon json no-key fallback " + entryName);
            }
            return plain;
        }

        byte[] modulePlain = tryDecryptModule(input);
        if (isLikelyPlainJson(modulePlain)) {
            log("zip addon json module fallback " + entryName);
            return modulePlain;
        }

        if (keys.isEmpty()) {
            throw new MissingZipKeyException("missing key/header for encrypted JSON (tried no-key/module)");
        }
        throw new IllegalStateException("native returned empty data (tried key/no-key/module)");
    }

    private static byte[] decryptMapDataWithKey(byte[] input, byte[] key) {
        if (key == null) {
            return null;
        }
        String text = new String(input, StandardCharsets.UTF_8);
        if (text.contains("@")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String[] parts = text.split("@");
            for (String part : parts) {
                byte[] chunk = LauncherMiscUtil.decryptFileDataUsekey(part.getBytes(StandardCharsets.UTF_8), key);
                if (chunk == null || chunk.length == 0) {
                    return null;
                }
                try {
                    out.write(chunk);
                } catch (IOException ignored) {
                    return null;
                }
            }
            return out.toByteArray();
        }
        return LauncherMiscUtil.decryptFileDataUsekey(input, key);
    }

    private static byte[] decryptMapDataWithoutKey(byte[] input) {
        String text = new String(input, StandardCharsets.UTF_8);
        if (text.contains("@")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String[] parts = text.split("@");
            for (String part : parts) {
                byte[] chunk = LauncherMiscUtil.decryptFileData(part.getBytes(StandardCharsets.UTF_8));
                if (chunk == null || chunk.length == 0) {
                    return null;
                }
                try {
                    out.write(chunk);
                } catch (IOException ignored) {
                    return null;
                }
            }
            return out.toByteArray();
        }
        return LauncherMiscUtil.decryptFileData(input);
    }

    private boolean repairDbDirectory(DocumentFile inputDb, DocumentFile outputDb, String label, byte[] key) throws IOException {
        File tempDb = createTempDirectory("dw_leveldb_dir_");
        try {
            copyDocumentTreeToLocal(inputDb, tempDb);
            DbRepairResult result = repairLocalDb(tempDb, key);
            copyLocalTreeToDocument(tempDb, outputDb);
            if (result.changed) {
                if (keepRepairArtifacts && result.oldValue != null) {
                    writeFile(outputDb, "mVillages.dw_merged_value.bak", "application/octet-stream", result.oldValue);
                }
                if (analyzeMapWal && result.oldValue != null) {
                    WalAnalysisBundle bundle = analyzeEncryptedValue(label + "/mVillages.merged", result.oldValue, key);
                    writeFile(outputDb, "mVillages.dw_merged_analysis.txt", "text/plain", bundle.report);
                    for (Map.Entry<String, byte[]> entry : bundle.binaries.entrySet()) {
                        writeFile(outputDb, "mVillages.dw_merged_analysis." + entry.getKey(),
                                "application/octet-stream", entry.getValue());
                    }
                }
                repaired++;
                log("repair merged db " + label + "/mVillages");
            }
            return true;
        } finally {
            deleteLocalTree(tempDb);
        }
    }

    private DbRepairResult repairLocalDb(File dbDir, byte[] mapKey) {
        byte[] key = "mVillages".getBytes(StandardCharsets.US_ASCII);
        DB db = null;
        try {
            db = new DB(dbDir);
            db.open();
            byte[] oldValue = db.get(key);
            if (oldValue != null && looksLikeEncryptedText(oldValue)) {
                byte[] repairedValue = null;
                if (mapDbRepairMode == MapDbRepairMode.DECRYPT_MVILLAGES) {
                    repairedValue = decryptMapDbValue(oldValue, mapKey);
                }
                if (repairedValue != null) {
                    db.put(key, repairedValue);
                    log("leveldb decrypt verified " + dbDir.getName()
                            + "/mVillages len=" + repairedValue.length);
                } else {
                    if (mapDbRepairMode == MapDbRepairMode.DECRYPT_MVILLAGES) {
                        log("leveldb decrypt unavailable, delete fallback "
                                + dbDir.getName() + "/mVillages");
                    }
                    db.delete(key);
                }
                try {
                    byte[] check = db.get(key);
                    if (repairedValue != null && check != null && Arrays.equals(check, repairedValue)) {
                        log("leveldb put verified " + dbDir.getName() + "/mVillages");
                    } else if (repairedValue == null && check == null) {
                        log("leveldb delete verified " + dbDir.getName() + "/mVillages");
                    } else {
                        log("leveldb repair check unexpected " + dbDir.getName()
                                + "/mVillages len=" + (check == null ? 0 : check.length));
                    }
                } catch (Throwable ignored) {
                    log("leveldb repair applied " + dbDir.getName() + "/mVillages");
                }
                return new DbRepairResult(true, oldValue);
            }
        } catch (Throwable t) {
            log("leveldb repair open failed " + dbDir.getName() + ": " + t.getMessage());
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return new DbRepairResult(false, null);
    }

    private byte[] decryptMapDbValue(byte[] encryptedValue, byte[] mapKey) {
        if (mapKey == null || mapKey.length == 0) {
            log("leveldb decrypt skipped: missing .level.key");
            return null;
        }
        try {
            byte[] out = LauncherMiscUtil.decryptFileDataUsekey(trimAscii(encryptedValue), mapKey);
            if (out == null || out.length == 0) {
                log("leveldb decrypt returned empty data");
                return null;
            }
            if ((out[0] & 0xff) != 0x0a) {
                log("leveldb decrypt rejected non-NBT result kind=" + describeBytes(out)
                        + " len=" + out.length);
                return null;
            }
            return out;
        } catch (Throwable t) {
            log("leveldb decrypt failed: " + t.getMessage());
            return null;
        }
    }

    private String mapDbRepairModeLabel() {
        if (mapDbRepairMode == MapDbRepairMode.DECRYPT_MVILLAGES) {
            return "decrypt mVillages with .level.key";
        }
        return "delete encrypted mVillages";
    }

    private File createTempDirectory(String prefix) throws IOException {
        File base = new File(context.getCacheDir(), prefix + System.nanoTime());
        if (!base.mkdirs()) {
            throw new IOException("cannot create temp db");
        }
        return base;
    }

    private void copyDocumentTreeToLocal(DocumentFile source, File target) throws IOException {
        DocumentFile[] children = source.listFiles();
        for (DocumentFile child : children) {
            String name = child.getName();
            if (name == null) {
                continue;
            }
            if (isAnalysisArtifactName(name.toLowerCase(Locale.US))) {
                continue;
            }
            if (child.isDirectory()) {
                File childDir = new File(target, name);
                if (!childDir.mkdirs() && !childDir.isDirectory()) {
                    throw new IOException("cannot create temp dir " + name);
                }
                copyDocumentTreeToLocal(child, childDir);
            } else {
                writeLocalFile(new File(target, name), readAll(child));
            }
        }
    }

    private void copyLocalTreeToDocument(File source, DocumentFile target) throws IOException {
        File[] files = source.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                DocumentFile outDir = target.findFile(file.getName());
                if (outDir == null) {
                    outDir = target.createDirectory(file.getName());
                }
                if (outDir == null) {
                    throw new IOException("cannot create output dir " + file.getName());
                }
                copyLocalTreeToDocument(file, outDir);
            } else {
                writeFile(target, file.getName(), "application/octet-stream", readLocalFile(file));
            }
        }
    }

    private void analyzeDbDirectoryLogs(DocumentFile inputDb, DocumentFile outputDb,
                                        byte[] key, String label) {
        for (DocumentFile child : inputDb.listFiles()) {
            String name = child.getName();
            if (name == null || !child.isFile() || !name.toLowerCase(Locale.US).endsWith(".log")) {
                continue;
            }
            try {
                byte[] data = readAll(child);
                if (isSuspiciousLevelDbWal(data)) {
                    writeWalAnalysis(child, outputDb, label + "/" + name, data, key, true);
                }
            } catch (Throwable t) {
                log("wal analysis failed " + label + "/" + name + ": " + t.getMessage());
            }
        }
    }

    private static Map<String, byte[]> readLocalFiles(File root) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        readLocalFiles(root, root, files);
        return files;
    }

    private static void readLocalFiles(File root, File current, Map<String, byte[]> files) throws IOException {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                readLocalFiles(root, child, files);
            } else {
                String relative = root.toURI().relativize(child.toURI()).getPath();
                files.put(relative, readLocalFile(child));
            }
        }
    }

    private static byte[] readLocalFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        }
    }

    private static void writeLocalFile(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create temp parent");
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    private static void deleteLocalTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteLocalTree(child);
                }
            }
        }
        file.delete();
    }

    private static final class DbRepairResult {
        final boolean changed;
        final byte[] oldValue;

        DbRepairResult(boolean changed, byte[] oldValue) {
            this.changed = changed;
            this.oldValue = oldValue;
        }
    }

    private static final class ZipDbRepairPlan {
        final Map<String, byte[]> replacements = new HashMap<>();
        final Map<String, byte[]> artifacts = new HashMap<>();
    }

    private byte[] readAll(DocumentFile file) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(file.getUri())) {
            if (in == null) {
                throw new IOException("cannot open input");
            }
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private void writeFile(DocumentFile dir, String name, String mime, byte[] data) throws IOException {
        DocumentFile existing = dir.findFile(name);
        if (existing != null) {
            existing.delete();
        }
        DocumentFile out = dir.createFile(mime, name);
        if (out == null) {
            throw new IOException("cannot create " + name);
        }
        try (OutputStream stream = context.getContentResolver().openOutputStream(out.getUri())) {
            if (stream == null) {
                throw new IOException("cannot open output");
            }
            stream.write(data);
        }
    }

    private byte[] readOptionalFile(DocumentFile dir, String name) throws IOException {
        DocumentFile file = dir.findFile(name);
        return file == null || !file.isFile() ? null : readAll(file);
    }

    private void writeUserKeyAnalysis(DocumentFile outputDir, String name,
                                      byte[] userKeyData, byte[] levelKeyData) throws IOException {
        String report = analyzeUserKey(name, userKeyData, levelKeyData);
        writeFile(outputDir, name + ".dw_user_key_analysis.txt", "text/plain",
                report.getBytes(StandardCharsets.UTF_8));
        log("user key analysis " + name);
    }

    private String analyzeUserKey(String label, byte[] userKeyData, byte[] levelKeyData) {
        StringBuilder report = new StringBuilder();
        report.append("Duowan user key analysis\n");
        report.append("label: ").append(label).append('\n');
        report.append("encrypted_len: ").append(userKeyData.length).append('\n');
        report.append("encrypted_kind: ").append(describeBytes(userKeyData)).append('\n');
        if (levelKeyData != null) {
            report.append("level_key_file_len: ").append(levelKeyData.length).append('\n');
            report.append("level_key_file_md5: ").append(md5Hex(levelKeyData)).append('\n');
        } else {
            report.append("level_key_file_md5: missing\n");
        }

        try {
            byte[] plain = LauncherMiscUtil.decryptFileData(trimAscii(userKeyData));
            if (plain == null) {
                report.append("decrypt_result: null\n");
                return report.toString();
            }
            report.append("decrypted_len: ").append(plain.length).append('\n');
            report.append("decrypted_kind: ").append(describeBytes(plain)).append('\n');
            report.append("decrypted_ascii: ").append(asciiPreview(plain, 260)).append('\n');
            try {
                JSONObject json = new JSONObject(new String(plain, StandardCharsets.UTF_8).trim());
                String userId = json.optString("userId", "");
                String md5 = json.optString("md5", "");
                report.append("json_userId: ").append(userId.isEmpty() ? "missing" : userId).append('\n');
                report.append("json_md5: ").append(md5.isEmpty() ? "missing" : md5).append('\n');
                if (levelKeyData != null && !md5.isEmpty()) {
                    report.append("json_md5_matches_level_key: ")
                            .append(md5.equalsIgnoreCase(md5Hex(levelKeyData)) ? "yes" : "no")
                            .append('\n');
                }
            } catch (Throwable t) {
                report.append("json_parse_error: ").append(t.getMessage()).append('\n');
            }
        } catch (Throwable t) {
            report.append("decrypt_error: ").append(t.getClass().getSimpleName())
                    .append(": ").append(t.getMessage()).append('\n');
        }
        return report.toString();
    }

    private static String md5Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(data);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                int value = b & 0xff;
                if (value < 0x10) {
                    out.append('0');
                }
                out.append(Integer.toHexString(value));
            }
            return out.toString();
        } catch (Throwable t) {
            return "error:" + t.getMessage();
        }
    }

    private static boolean startsWithMagic(byte[] data) {
        return startsWithMagicAt(data, 0);
    }

    private static boolean startsWithMagicAt(byte[] data, int offset) {
        if (offset < 0 || data.length < offset + MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[offset + i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPng(byte[] data) {
        return data.length >= 8
                && (data[0] & 0xff) == 0x89
                && data[1] == 0x50
                && data[2] == 0x4e
                && data[3] == 0x47
                && data[4] == 0x0d
                && data[5] == 0x0a
                && data[6] == 0x1a
                && data[7] == 0x0a;
    }

    private static boolean isZipLike(String lower) {
        return lower.endsWith(".modpkg") || lower.endsWith(".zip")
                || lower.endsWith(".mcpack") || lower.endsWith(".mcworld");
    }

    private static boolean looksLikeEncryptedText(byte[] data) {
        byte[] trimmed = trimAscii(data);
        int limit = Math.min(trimmed.length, 12);
        int i = 0;
        while (i < limit && trimmed[i] >= '0' && trimmed[i] <= '9') {
            i++;
        }
        return i > 0 && i < trimmed.length && trimmed[i] == '#';
    }

    private static boolean looksLikeSegmentedEncryptedText(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8).trim();
        if (!text.contains("@")) {
            return false;
        }
        String[] parts = text.split("@");
        if (parts.length < 2) {
            return false;
        }
        for (String part : parts) {
            int hash = part.indexOf('#');
            if (hash <= 0 || hash > 8) {
                return false;
            }
            for (int i = 0; i < hash; i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            if (part.length() <= hash + 1) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasSibling(DocumentFile dir, String siblingName) {
        for (DocumentFile child : dir.listFiles()) {
            if (siblingName.equals(child.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMetadataName(String lower) {
        return lower.equals(".user.key") || lower.equals(".level.key") || lower.equals(".blur") || lower.equals(".texture.key");
    }

    private static boolean isAnalysisArtifactName(String lower) {
        return lower.contains(".dw_wal_analysis") || lower.contains(".dw_user_key_analysis");
    }

    private static boolean isMetadataPath(String lower) {
        return isMetadataName(lower)
                || lower.endsWith("/.user.key") || lower.endsWith("\\.user.key")
                || lower.endsWith("/.level.key") || lower.endsWith("\\.level.key")
                || lower.endsWith("/.blur") || lower.endsWith("\\.blur")
                || lower.endsWith("/.texture.key") || lower.endsWith("\\.texture.key");
    }

    private static boolean isKeyMetadataName(String lower) {
        return lower.equals(".level.key") || lower.equals(".blur") || lower.equals(".texture.key");
    }

    private static boolean isMcEncryptedName(String lower) {
        return lower.endsWith("-mc") || lower.endsWith("-mc.json")
                || lower.endsWith(".json-mc") || lower.endsWith("pack_manifest.json-mc")
                || lower.endsWith("pack_manifest-mc.json") || lower.endsWith("manifest-mc.json");
    }

    private static boolean isAddonOrResourceJson(String lower) {
        return isMcEncryptedName(lower) || lower.endsWith(".json");
    }

    private static boolean isModpkgScriptLikeName(String lower) {
        return lower.endsWith(".js") || lower.endsWith(".json") || lower.endsWith(".list")
                || lower.endsWith(".txt") || lower.endsWith(".cfg") || lower.endsWith(".ini");
    }

    private static boolean isLikelyPlaceholderJson(String lower, byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8).trim();
        return lower.endsWith(".json") && "{}".equals(text);
    }

    private static boolean isLikelyPlainJson(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        String text = new String(data, StandardCharsets.UTF_8).trim();
        return text.startsWith("{") || text.startsWith("[");
    }

    private static boolean isLikelyText(byte[] data) {
        if (data.length == 0) {
            return false;
        }
        int limit = Math.min(data.length, 4096);
        int printable = 0;
        for (int i = 0; i < limit; i++) {
            int b = data[i] & 0xff;
            if (b == 9 || b == 10 || b == 13 || (b >= 32 && b < 127) || b >= 0x80) {
                printable++;
            }
        }
        return printable >= limit * 9 / 10;
    }

    private static boolean isInDbDir(String lower) {
        return lower.contains("/db/") || lower.contains("\\db\\");
    }

    private static String parentPath(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash < 0 ? "" : name.substring(0, slash + 1);
    }

    private static String dbRootPrefix(String name) {
        String normalized = name.replace('\\', '/');
        int index = normalized.toLowerCase(Locale.US).indexOf("/db/");
        if (index < 0) {
            if (normalized.toLowerCase(Locale.US).startsWith("db/")) {
                return name.substring(0, 3);
            }
            return null;
        }
        return name.substring(0, index + 4);
    }

    private static final class ZipKeySet {
        private final List<KeyEntry> entries = new ArrayList<>();

        void add(String prefix, byte[] key) {
            entries.add(new KeyEntry(prefix == null ? "" : prefix, key));
        }

        boolean isEmpty() {
            return entries.isEmpty();
        }

        List<byte[]> keysFor(String entryName) {
            List<KeyEntry> sorted = new ArrayList<>(entries);
            Collections.sort(sorted, new Comparator<KeyEntry>() {
                @Override
                public int compare(KeyEntry a, KeyEntry b) {
                    return b.prefix.length() - a.prefix.length();
                }
            });
            List<byte[]> result = new ArrayList<>();
            for (KeyEntry entry : sorted) {
                if (entryName.startsWith(entry.prefix)) {
                    result.add(entry.key);
                }
            }
            for (KeyEntry entry : sorted) {
                if (!result.contains(entry.key)) {
                    result.add(entry.key);
                }
            }
            return result;
        }

        byte[] firstKeyFor(String entryName) {
            List<byte[]> keys = keysFor(entryName);
            return keys.isEmpty() ? null : keys.get(0);
        }
    }

    private static final class KeyEntry {
        final String prefix;
        final byte[] key;

        KeyEntry(String prefix, byte[] key) {
            this.prefix = prefix;
            this.key = key;
        }
    }

    private enum AttemptMode {
        FILE_NO_KEY,
        FILE_WITH_KEY,
        MODULE
    }

    private static final class WalAnalysisBundle {
        final byte[] report;
        final Map<String, byte[]> binaries;

        WalAnalysisBundle(byte[] report, Map<String, byte[]> binaries) {
            this.report = report;
            this.binaries = binaries;
        }
    }

    private static final class WalRecord {
        final int crc;
        final int payloadLen;
        final int recordType;
        final long sequence;
        final int count;
        final int tag;
        final String key;
        final byte[] value;

        WalRecord(int crc, int payloadLen, int recordType, long sequence,
                  int count, int tag, String key, byte[] value) {
            this.crc = crc;
            this.payloadLen = payloadLen;
            this.recordType = recordType;
            this.sequence = sequence;
            this.count = count;
            this.tag = tag;
            this.key = key;
            this.value = value;
        }
    }

    private static boolean isSuspiciousLevelDbWal(byte[] data) {
        if (data.length < 7 + 12) {
            return false;
        }
        int payloadLen = (data[4] & 0xff) | ((data[5] & 0xff) << 8);
        int recordType = data[6] & 0xff;
        if (recordType != 1 || payloadLen + 7 != data.length) {
            return false;
        }

        int pos = 7 + 8;
        if (pos + 4 > data.length) {
            return false;
        }
        int count = (data[pos] & 0xff)
                | ((data[pos + 1] & 0xff) << 8)
                | ((data[pos + 2] & 0xff) << 16)
                | ((data[pos + 3] & 0xff) << 24);
        pos += 4;
        if (count != 1 || pos >= data.length || (data[pos++] & 0xff) != 1) {
            return false;
        }

        Varint keyLen = readVarint(data, pos);
        if (keyLen == null) {
            return false;
        }
        pos = keyLen.next;
        if (keyLen.value < 0 || pos + keyLen.value > data.length) {
            return false;
        }
        String key = new String(data, pos, keyLen.value, StandardCharsets.US_ASCII);
        pos += keyLen.value;
        if (!"mVillages".equals(key)) {
            return false;
        }

        Varint valueLen = readVarint(data, pos);
        if (valueLen == null) {
            return false;
        }
        pos = valueLen.next;
        if (valueLen.value <= 0 || pos + valueLen.value > data.length) {
            return false;
        }
        byte[] value = Arrays.copyOfRange(data, pos, pos + valueLen.value);
        return looksLikeEncryptedText(value);
    }

    private void writeWalAnalysis(DocumentFile originalDir, DocumentFile outputDir, String label,
                                  byte[] walData, byte[] key, boolean alsoWriteOutput) {
        WalAnalysisBundle bundle = analyzeWalData(label, walData, key);
        String baseName = safeArtifactName(label) + ".dw_wal_analysis";
        try {
            writeFile(originalDir.getParentFile(), baseName + ".txt", "text/plain", bundle.report);
            for (Map.Entry<String, byte[]> entry : bundle.binaries.entrySet()) {
                writeFile(originalDir.getParentFile(), baseName + "." + entry.getKey(),
                        "application/octet-stream", entry.getValue());
            }
            log("wal analysis wrote original dir " + baseName);
        } catch (Throwable t) {
            log("wal analysis original write failed " + label + ": " + t.getMessage());
            alsoWriteOutput = true;
        }
        if (alsoWriteOutput) {
            try {
                writeFile(outputDir, baseName + ".txt", "text/plain", bundle.report);
                for (Map.Entry<String, byte[]> entry : bundle.binaries.entrySet()) {
                    writeFile(outputDir, baseName + "." + entry.getKey(),
                            "application/octet-stream", entry.getValue());
                }
                log("wal analysis wrote output dir " + baseName);
            } catch (Throwable t) {
                log("wal analysis output write failed " + label + ": " + t.getMessage());
            }
        }
    }

    private WalAnalysisBundle analyzeWalData(String label, byte[] walData, byte[] key) {
        WalRecord record = parseSuspiciousWal(walData);
        if (record == null) {
            return new WalAnalysisBundle(("Not a recognized Duowan mVillages WAL: " + label + "\n")
                    .getBytes(StandardCharsets.UTF_8), new HashMap<String, byte[]>());
        }
        return analyzeEncryptedValue(label, record.value, key, record);
    }

    private WalAnalysisBundle analyzeEncryptedValue(String label, byte[] value, byte[] key) {
        return analyzeEncryptedValue(label, value, key, null);
    }

    private WalAnalysisBundle analyzeEncryptedValue(String label, byte[] value, byte[] key, WalRecord record) {
        Map<String, byte[]> binaries = new HashMap<>();
        StringBuilder report = new StringBuilder();
        report.append("Duowan map WAL analysis\n");
        report.append("label: ").append(label).append('\n');
        if (record != null) {
            report.append("crc32c_masked_le: 0x").append(hexInt(record.crc)).append('\n');
            report.append("payload_len: ").append(record.payloadLen).append('\n');
            report.append("record_type: ").append(record.recordType).append('\n');
            report.append("sequence: ").append(record.sequence).append('\n');
            report.append("batch_count: ").append(record.count).append('\n');
            report.append("write_tag: ").append(record.tag).append('\n');
            report.append("key: ").append(record.key).append('\n');
        }
        report.append("value_len: ").append(value.length).append('\n');
        report.append("value_ascii: ").append(asciiPreview(value, 220)).append('\n');
        byte[] decoded = decodeLengthPrefixedBase64(value);
        if (decoded != null) {
            binaries.put("base64_decoded.bin", decoded);
            report.append("base64_decoded_len: ").append(decoded.length).append('\n');
            report.append("base64_decoded_hex: ").append(hexPreview(decoded, 128)).append('\n');
            report.append("base64_decoded_ascii: ").append(asciiPreview(decoded, 160)).append('\n');
        } else {
            report.append("base64_decoded_len: failed\n");
        }
        report.append("key_available: ").append(key != null ? "yes" : "no").append('\n');
        if (key != null) {
            report.append("key_hex: ").append(hexPreview(key, key.length)).append('\n');
        }

        addDecryptAttempt(report, binaries, "value_no_key.bin", value, null, AttemptMode.FILE_NO_KEY);
        addDecryptAttempt(report, binaries, "value_module.bin", value, null, AttemptMode.MODULE);
        if (key != null) {
            addDecryptAttempt(report, binaries, "value_with_key.bin", value, key, AttemptMode.FILE_WITH_KEY);
        }
        if (decoded != null) {
            addDecryptAttempt(report, binaries, "decoded_no_key.bin", decoded, null, AttemptMode.FILE_NO_KEY);
            addDecryptAttempt(report, binaries, "decoded_module.bin", decoded, null, AttemptMode.MODULE);
            if (key != null) {
                addDecryptAttempt(report, binaries, "decoded_with_key.bin", decoded, key, AttemptMode.FILE_WITH_KEY);
            }
        }
        return new WalAnalysisBundle(report.toString().getBytes(StandardCharsets.UTF_8), binaries);
    }

    private static void addDecryptAttempt(StringBuilder report, Map<String, byte[]> binaries,
                                          String name, byte[] input, byte[] key, AttemptMode mode) {
        report.append('\n').append("attempt: ").append(name).append('\n');
        try {
            byte[] out;
            if (mode == AttemptMode.FILE_WITH_KEY) {
                out = LauncherMiscUtil.decryptFileDataUsekey(trimAscii(input), key);
            } else if (mode == AttemptMode.MODULE) {
                out = LauncherMiscUtil.decryptModule(input, input.length);
            } else {
                out = LauncherMiscUtil.decryptFileData(trimAscii(input));
            }
            if (out == null) {
                report.append("result: null\n");
                return;
            }
            report.append("result_len: ").append(out.length).append('\n');
            report.append("result_kind: ").append(describeBytes(out)).append('\n');
            report.append("result_hex: ").append(hexPreview(out, 160)).append('\n');
            report.append("result_ascii: ").append(asciiPreview(out, 220)).append('\n');
            if (out.length > 0) {
                binaries.put(name, out);
            }
        } catch (Throwable t) {
            report.append("error: ").append(t.getClass().getSimpleName()).append(": ")
                    .append(t.getMessage()).append('\n');
        }
    }

    private static WalRecord parseSuspiciousWal(byte[] data) {
        if (data.length < 7 + 12) {
            return null;
        }
        int payloadLen = (data[4] & 0xff) | ((data[5] & 0xff) << 8);
        int recordType = data[6] & 0xff;
        if (recordType != 1 || payloadLen + 7 != data.length) {
            return null;
        }
        int pos = 7;
        long sequence = readLittleLong(data, pos);
        pos += 8;
        int count = (data[pos] & 0xff)
                | ((data[pos + 1] & 0xff) << 8)
                | ((data[pos + 2] & 0xff) << 16)
                | ((data[pos + 3] & 0xff) << 24);
        pos += 4;
        if (count != 1 || pos >= data.length) {
            return null;
        }
        int tag = data[pos++] & 0xff;
        if (tag != 1) {
            return null;
        }
        Varint keyLen = readVarint(data, pos);
        if (keyLen == null || keyLen.value < 0) {
            return null;
        }
        pos = keyLen.next;
        if (pos + keyLen.value > data.length) {
            return null;
        }
        String key = new String(data, pos, keyLen.value, StandardCharsets.US_ASCII);
        pos += keyLen.value;
        Varint valueLen = readVarint(data, pos);
        if (valueLen == null || valueLen.value <= 0) {
            return null;
        }
        pos = valueLen.next;
        if (pos + valueLen.value > data.length || !"mVillages".equals(key)) {
            return null;
        }
        byte[] value = Arrays.copyOfRange(data, pos, pos + valueLen.value);
        if (!looksLikeEncryptedText(value)) {
            return null;
        }
        return new WalRecord(u32Raw(data, 0), payloadLen, recordType, sequence, count, tag, key, value);
    }

    private static byte[] decodeLengthPrefixedBase64(byte[] value) {
        String text = new String(trimAscii(value), StandardCharsets.US_ASCII);
        int hash = text.indexOf('#');
        if (hash <= 0) {
            return null;
        }
        try {
            int expectedLen = Integer.parseInt(text.substring(0, hash));
            byte[] decoded = Base64.decode(text.substring(hash + 1), Base64.DEFAULT);
            return decoded.length == expectedLen ? decoded : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Map<String, byte[]> prefixArtifacts(String prefix, Map<String, byte[]> artifacts) {
        Map<String, byte[]> result = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : artifacts.entrySet()) {
            result.put(prefix + entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static String safeArtifactName(String label) {
        String name = label.replace('\\', '_').replace('/', '_').replace(':', '_');
        if (name.length() > 120) {
            name = name.substring(name.length() - 120);
        }
        return name;
    }

    private static String describeBytes(byte[] data) {
        if (data.length == 0) {
            return "empty";
        }
        if (isPng(data)) {
            return "png";
        }
        if (data.length >= 4 && data[0] == 'P' && data[1] == 'K') {
            return "zip";
        }
        if (isLikelyPlainJson(data)) {
            return "json";
        }
        if ((data[0] & 0xff) == 0x0a) {
            return "possible-nbt";
        }
        if (isLikelyText(data)) {
            return "text";
        }
        return "binary";
    }

    private static String asciiPreview(byte[] data, int max) {
        int limit = Math.min(data.length, max);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            int b = data[i] & 0xff;
            if (b == '\n') {
                out.append("\\n");
            } else if (b == '\r') {
                out.append("\\r");
            } else if (b == '\t') {
                out.append("\\t");
            } else if (b >= 32 && b < 127) {
                out.append((char) b);
            } else {
                out.append('.');
            }
        }
        if (data.length > limit) {
            out.append("...");
        }
        return out.toString();
    }

    private static String hexPreview(byte[] data, int max) {
        int limit = Math.min(data.length, max);
        StringBuilder out = new StringBuilder(limit * 3);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                out.append(' ');
            }
            int b = data[i] & 0xff;
            if (b < 16) {
                out.append('0');
            }
            out.append(Integer.toHexString(b).toUpperCase(Locale.US));
        }
        if (data.length > limit) {
            out.append(" ...");
        }
        return out.toString();
    }

    private static String hexInt(int value) {
        String text = Integer.toHexString(value).toUpperCase(Locale.US);
        StringBuilder out = new StringBuilder();
        for (int i = text.length(); i < 8; i++) {
            out.append('0');
        }
        return out.append(text).toString();
    }

    private static int u32Raw(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private static long readLittleLong(byte[] data, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (data[offset + i] & 0xffL);
        }
        return value;
    }

    private static boolean containsSuspiciousLevelDbValue(byte[] data) {
        return findSuspiciousLevelDbMarker(data) >= 0;
    }

    private static byte[] extractSuspiciousLevelDbSlice(byte[] data) {
        int marker = findSuspiciousLevelDbMarker(data);
        if (marker < 0) {
            return new byte[0];
        }
        int start = Math.max(0, marker - 48);
        int end = Math.min(data.length, marker + 256);
        return Arrays.copyOfRange(data, start, end);
    }

    private static int findSuspiciousLevelDbMarker(byte[] data) {
        byte[] key = "mVillages".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i <= data.length - key.length; i++) {
            boolean match = true;
            for (int j = 0; j < key.length; j++) {
                if (data[i + j] != key[j]) {
                    match = false;
                    break;
                }
            }
            if (!match) {
                continue;
            }
            int end = Math.min(data.length, i + 160);
            for (int p = i + key.length; p < end; p++) {
                if (data[p] == '#') {
                    int start = p - 1;
                    while (start >= i + key.length && data[start] >= '0' && data[start] <= '9') {
                        start--;
                    }
                    if (start < p - 1) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private static Varint readVarint(byte[] data, int offset) {
        int result = 0;
        int shift = 0;
        int pos = offset;
        while (pos < data.length && shift <= 28) {
            int b = data[pos++] & 0xff;
            result |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return new Varint(result, pos);
            }
            shift += 7;
        }
        return null;
    }

    private static final class Varint {
        final int value;
        final int next;

        Varint(int value, int next) {
            this.value = value;
            this.next = next;
        }
    }

    private static String stripMcSuffix(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".json-mc")) {
            return name.substring(0, name.length() - 3);
        }
        if (lower.endsWith("-mc.json")) {
            return name.substring(0, name.length() - 8) + ".json";
        }
        if (lower.endsWith("-mc")) {
            return name.substring(0, name.length() - 3);
        }
        return name;
    }

    private static String outputNameForEntry(String entryName, String lower, byte[] data,
                                             boolean decryptedEntry, boolean directory) {
        if (directory) {
            return stripMcSuffix(entryName);
        }
        if (isMcEncryptedName(lower) && (decryptedEntry || isLikelyPlainJson(data))) {
            return stripMcSuffix(entryName);
        }
        if (!decryptedEntry && lower.endsWith(".json") && looksLikeEncryptedText(data)) {
            return entryName + ".dw_encrypted";
        }
        return entryName;
    }

    private static byte[] trimAscii(byte[] data) {
        int start = 0;
        int end = data.length;
        while (start < end && data[start] <= 0x20) {
            start++;
        }
        while (end > start && data[end - 1] <= 0x20) {
            end--;
        }
        return start == 0 && end == data.length ? data : Arrays.copyOfRange(data, start, end);
    }

    private static String mimeFor(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".json") || lower.endsWith(".js")) {
            return "application/json";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".zip") || lower.endsWith(".mcpack") || lower.endsWith(".mcworld")) {
            return "application/zip";
        }
        return "application/octet-stream";
    }

    private static String containerMimeFor(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".zip") || lower.endsWith(".mcpack") || lower.endsWith(".mcworld")) {
            return "application/zip";
        }
        return "application/octet-stream";
    }

    private void log(String message) {
        logger.log(chineseLogs ? zhLog(message) : message);
    }

    private String zhLog(String message) {
        if (message == null) {
            return "";
        }
        if (message.startsWith("Output: ")) {
            return "输出目录：" + message.substring("Output: ".length());
        }
        if (message.startsWith("Map DB repair: ")) {
            return "地图数据库修复：" + zhOnOff(message.substring("Map DB repair: ".length()));
        }
        if (message.startsWith("Map DB repair mode: ")) {
            return "地图数据库修复模式：" + zhRepairMode(message.substring("Map DB repair mode: ".length()));
        }
        if (message.startsWith("Keep repair artifacts: ")) {
            return "保留修复调试文件：" + zhOnOff(message.substring("Keep repair artifacts: ".length()));
        }
        if (message.startsWith("Analyze map log decryption: ")) {
            return "分析地图 Log 解密：" + zhOnOff(message.substring("Analyze map log decryption: ".length()));
        }
        if (message.startsWith("Analyze user key: ")) {
            return "分析 User Key：" + zhOnOff(message.substring("Analyze user key: ".length()));
        }
        if (message.startsWith("Done. ")) {
            return "完成。" + message.substring("Done. ".length())
                    .replace("filesDecryptedOrRepacked=", "文件解密或重新打包=")
                    .replace("repaired=", "修复=")
                    .replace("copied=", "复制=")
                    .replace("skipped=", "跳过=")
                    .replace("fileFailed=", "文件失败=")
                    .replace("zipEntriesDecrypted=", "ZIP内文件解密=")
                    .replace("zipEntriesFailed=", "ZIP内文件失败=")
                    .replace("zipEntriesMissingKey=", "ZIP内文件缺少Key=");
        }
        if (message.startsWith("FAIL ")) {
            return "失败 " + message.substring("FAIL ".length());
        }
        if (message.startsWith("db repair failed ")) {
            return "数据库修复失败 " + message.substring("db repair failed ".length());
        }
        if (message.startsWith("repair wal skip ")) {
            return "修复：跳过已注入的 WAL " + message.substring("repair wal skip ".length());
        }
        if (message.startsWith("repair merged db ")) {
            return "修复：已处理合并进数据库的 " + message.substring("repair merged db ".length());
        }
        if (message.startsWith("warn ldb may contain merged mVillages marker: ")) {
            return "警告：LDB 可能包含已合并的 mVillages 标记："
                    + message.substring("warn ldb may contain merged mVillages marker: ".length());
        }
        if (message.startsWith("missing key/header for encrypted file ")) {
            return "缺少 key 或加密头，无法解密文件："
                    + message.substring("missing key/header for encrypted file ".length());
        }
        if (message.startsWith("zip partial ")) {
            return "ZIP 部分完成 " + message.substring("zip partial ".length())
                    .replace("entriesFailed=", "内部失败=")
                    .replace("missingKey=", "缺少Key=");
        }
        if (message.startsWith("zip utf8 failed, retry gbk: ")) {
            return "ZIP UTF-8 文件名读取失败，改用 GBK 重试："
                    + message.substring("zip utf8 failed, retry gbk: ".length());
        }
        if (message.startsWith("zip key ")) {
            return "ZIP 发现 key：" + message.substring("zip key ".length());
        }
        if (message.startsWith("zip skip metadata ")) {
            return "ZIP 跳过元数据：" + message.substring("zip skip metadata ".length());
        }
        if (message.startsWith("zip user key analysis ")) {
            return "ZIP 已生成 User Key 分析：" + message.substring("zip user key analysis ".length());
        }
        if (message.startsWith("zip wal analysis ")) {
            return "ZIP 已生成 WAL 分析：" + message.substring("zip wal analysis ".length());
        }
        if (message.startsWith("zip keep wal artifact ")) {
            return "ZIP 保留 WAL 调试文件：" + message.substring("zip keep wal artifact ".length());
        }
        if (message.startsWith("zip repair wal skip ")) {
            return "ZIP 修复：跳过已注入的 WAL " + message.substring("zip repair wal skip ".length());
        }
        if (message.startsWith("zip repair merged db ")) {
            return "ZIP 修复：已处理合并进数据库的 " + message.substring("zip repair merged db ".length());
        }
        if (message.startsWith("zip merged db repair failed ")) {
            return "ZIP 合并数据库修复失败 " + message.substring("zip merged db repair failed ".length());
        }
        if (message.startsWith("zip entry fail ")) {
            return "ZIP 内文件失败 " + message.substring("zip entry fail ".length());
        }
        if (message.startsWith("zip skip placeholder ")) {
            return "ZIP 跳过占位文件：" + message.substring("zip skip placeholder ".length());
        }
        if (message.startsWith("zip skip duplicate ")) {
            return "ZIP 跳过重复文件：" + message.substring("zip skip duplicate ".length());
        }
        if (message.startsWith("zip add repaired db file ")) {
            return "ZIP 添加已修复数据库文件：" + message.substring("zip add repaired db file ".length());
        }
        if (message.startsWith("zip keep db artifact ")) {
            return "ZIP 保留数据库调试文件：" + message.substring("zip keep db artifact ".length());
        }
        if (message.startsWith("zip add wal analysis ")) {
            return "ZIP 添加 WAL 分析文件：" + message.substring("zip add wal analysis ".length());
        }
        if (message.startsWith("zip ")) {
            return "ZIP 已处理：" + message.substring("zip ".length());
        }
        if (message.startsWith("leveldb delete verified ")) {
            return "LevelDB 删除已确认：" + message.substring("leveldb delete verified ".length());
        }
        if (message.startsWith("leveldb delete still present ")) {
            return "LevelDB 删除后仍存在：" + message.substring("leveldb delete still present ".length());
        }
        if (message.startsWith("leveldb decrypt verified ")) {
            return "LevelDB 解密写回成功：" + message.substring("leveldb decrypt verified ".length());
        }
        if (message.startsWith("leveldb put verified ")) {
            return "LevelDB 写回已确认：" + message.substring("leveldb put verified ".length());
        }
        if (message.startsWith("leveldb decrypt unavailable, delete fallback ")) {
            return "LevelDB 解密不可用，已退回删除：" + message.substring("leveldb decrypt unavailable, delete fallback ".length());
        }
        if (message.startsWith("leveldb decrypt skipped: missing .level.key")) {
            return "LevelDB 解密跳过：缺少 .level.key";
        }
        if (message.startsWith("leveldb decrypt returned empty data")) {
            return "LevelDB 解密返回空数据";
        }
        if (message.startsWith("leveldb decrypt rejected non-NBT result kind=")) {
            return "LevelDB 解密结果不是 NBT，已拒绝写回：" + message.substring("leveldb decrypt rejected non-NBT result kind=".length());
        }
        if (message.startsWith("leveldb decrypt failed: ")) {
            return "LevelDB 解密失败：" + message.substring("leveldb decrypt failed: ".length());
        }
        if (message.startsWith("leveldb repair open failed ")) {
            return "LevelDB 打开修复失败 " + message.substring("leveldb repair open failed ".length());
        }
        if (message.startsWith("leveldb repair applied ")) {
            return "LevelDB 修复已应用：" + message.substring("leveldb repair applied ".length());
        }
        if (message.startsWith("leveldb repair check unexpected ")) {
            return "LevelDB 修复校验结果异常：" + message.substring("leveldb repair check unexpected ".length());
        }
        if (message.startsWith("wal analysis wrote original dir ")) {
            return "WAL 分析已写入原目录：" + message.substring("wal analysis wrote original dir ".length());
        }
        if (message.startsWith("wal analysis original write failed ")) {
            return "WAL 分析写入原目录失败 " + message.substring("wal analysis original write failed ".length());
        }
        if (message.startsWith("wal analysis wrote output dir ")) {
            return "WAL 分析已写入输出目录：" + message.substring("wal analysis wrote output dir ".length());
        }
        if (message.startsWith("wal analysis output write failed ")) {
            return "WAL 分析写入输出目录失败 " + message.substring("wal analysis output write failed ".length());
        }
        if (message.startsWith("user key analysis ")) {
            return "User Key 分析已生成：" + message.substring("user key analysis ".length());
        }
        if (message.startsWith("header ")) {
            return "加密头文件已解密：" + message.substring("header ".length());
        }
        if (message.startsWith("embedded image ")) {
            return "内嵌加密图片已解密：" + message.substring("embedded image ".length());
        }
        if (message.startsWith("json-mc ")) {
            return "json-mc 已解密：" + message.substring("json-mc ".length());
        }
        if (message.startsWith("residual name ")) {
            return "残留加密文件名：" + message.substring("residual name ".length());
        }
        if (message.startsWith("residual magic ")) {
            return "残留加密头：" + message.substring("residual magic ".length());
        }
        if (message.startsWith("residual encrypted json ")) {
            return "残留加密 JSON：" + message.substring("residual encrypted json ".length());
        }
        if (message.startsWith("residual db marker ")) {
            return "残留数据库标记：" + message.substring("residual db marker ".length());
        }
        if (message.startsWith("residual missing manifest ")) {
            return "残留缺失 MANIFEST：" + message.substring("residual missing manifest ".length());
        }
        if (message.startsWith("SUCCESSFUL RESOURCES")) {
            return message.replace("SUCCESSFUL RESOURCES", "成功资源");
        }
        if (message.startsWith("FAILED RESOURCES")) {
            return message.replace("FAILED RESOURCES", "失败资源");
        }
        if (message.startsWith("none")) {
            return "无";
        }
        return message;
    }

    private static String zhOnOff(String value) {
        return "on".equals(value) ? "开启" : ("off".equals(value) ? "关闭" : value);
    }

    private static String zhRepairMode(String value) {
        if ("delete encrypted mVillages".equals(value)) {
            return "删除加密的 mVillages";
        }
        if ("decrypt mVillages with .level.key".equals(value)) {
            return "用 .level.key 解密 mVillages";
        }
        return value;
    }
}
