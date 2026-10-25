package com.mahanapps.omnideck.core;

import java.io.File;
import java.io.IOException;

/**
 * Enforces strict jail confinement preventing directory traversal exploits.
 */
public final class PathJail {

    private PathJail() {}

    /**
     * Resolves a child path relative to a jail root and verifies that the canonical
     * path strictly resides within the jail root prefix.
     *
     * @return Canonical File if safe, or null if traversal/escape detected.
     */
    public static File resolveSafe(File jailRoot, String relativePath) {
        if (jailRoot == null || relativePath == null) {
            return null;
        }

        if (relativePath.contains("\0")) {
            return null; // Null-byte injection attempt
        }

        // Reject Windows-style separators or backslash tricks
        if (relativePath.contains("\\")) {
            return null;
        }

        // Reject encoded traversal sequences (%2e%2e, %2f, etc.)
        String lower = relativePath.toLowerCase();
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")) {
            return null;
        }

        try {
            File target = new File(jailRoot, relativePath);
            File canonicalJail = jailRoot.getCanonicalFile();
            File canonicalTarget = target.getCanonicalFile();

            if (isInside(canonicalJail, canonicalTarget)) {
                return canonicalTarget;
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Checks if target is strictly within the root directory (or is the root itself).
     */
    public static boolean isInside(File root, File target) {
        try {
            String rootPath = root.getCanonicalPath();
            String targetPath = target.getCanonicalPath();

            if (!rootPath.endsWith(File.separator)) {
                rootPath = rootPath + File.separator;
            }

            return targetPath.startsWith(rootPath) || target.getCanonicalPath().equals(root.getCanonicalPath());
        } catch (IOException e) {
            return false;
        }
    }
}
