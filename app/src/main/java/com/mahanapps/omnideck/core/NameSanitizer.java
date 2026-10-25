package com.mahanapps.omnideck.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/**
 * Sanitizes uploaded filenames preserving full Unicode scripts (Hindi, Devanagari, emojis)
 * while eliminating filesystem exploits and accidental overwrites.
 */
public final class NameSanitizer {

    private static final int MAX_BYTE_LEN = 120;

    private NameSanitizer() {}

    /**
     * Sanitizes a raw client-supplied filename.
     */
    public static String sanitize(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            return "unnamed_file";
        }

        // Unicode normalization (NFC)
        String clean = Normalizer.normalize(rawName.trim(), Normalizer.Form.NFC);

        // Replace path separators with underscores so traversal is impossible
        clean = clean.replace('\\', '_').replace('/', '_');

        // Replace illegal filesystem characters
        clean = clean.replaceAll("[<>:\"|?*\0]", "_");
        clean = clean.replaceAll("_+", "_");

        // Trim leading and trailing dots and spaces
        while (clean.startsWith(".") || clean.startsWith("_") || clean.startsWith(" ")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith(".") || clean.endsWith(" ")) {
            clean = clean.substring(0, clean.length() - 1);
        }

        if (clean.isEmpty()) {
            clean = "unnamed_file";
        }

        // Separate name and extension
        String namePart = clean;
        String extPart = "";
        int dotIdx = clean.lastIndexOf('.');
        if (dotIdx > 0 && dotIdx < clean.length() - 1) {
            namePart = clean.substring(0, dotIdx);
            extPart = clean.substring(dotIdx); // includes dot
        }

        while (namePart.endsWith("_") || namePart.endsWith(" ")) {
            namePart = namePart.substring(0, namePart.length() - 1);
        }
        if (namePart.isEmpty()) {
            namePart = "file";
        }

        // Enforce maximum byte length preserving extension
        byte[] extBytes = extPart.getBytes(StandardCharsets.UTF_8);
        int maxNameBytes = Math.max(10, MAX_BYTE_LEN - extBytes.length);

        byte[] nameBytes = namePart.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > maxNameBytes) {
            // Trim preserving valid UTF-8 boundary
            String trimmed = namePart;
            while (trimmed.getBytes(StandardCharsets.UTF_8).length > maxNameBytes && trimmed.length() > 1) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            namePart = trimmed;
        }

        return namePart + extPart;
    }

    /**
     * Finds a unique non-colliding file name in the destination directory.
     * Uses case-insensitive matching to protect FAT32 / FUSE / sdcardfs filesystems.
     */
    public static File resolveUniqueFile(File destDir, String sanitizedName) {
        File candidate = new File(destDir, sanitizedName);
        if (!caseInsensitiveExists(destDir, candidate.getName())) {
            return candidate;
        }

        String base = sanitizedName;
        String ext = "";
        int dot = sanitizedName.lastIndexOf('.');
        if (dot > 0) {
            base = sanitizedName.substring(0, dot);
            ext = sanitizedName.substring(dot);
        }

        for (int i = 1; i < 10000; i++) {
            String nextName = base + " (" + i + ")" + ext;
            if (!caseInsensitiveExists(destDir, nextName)) {
                return new File(destDir, nextName);
            }
        }

        return new File(destDir, base + "_" + System.currentTimeMillis() + ext);
    }

    private static boolean caseInsensitiveExists(File dir, String name) {
        if (!dir.exists()) {
            return false;
        }
        String[] files = dir.list();
        if (files == null) {
            return false;
        }
        for (String f : files) {
            if (f.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
