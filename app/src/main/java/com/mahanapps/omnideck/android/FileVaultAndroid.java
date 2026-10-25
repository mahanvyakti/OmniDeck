package com.mahanapps.omnideck.android;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.mahanapps.omnideck.core.PathJail;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Android File Vault manager.
 * Supports user-configurable, persistent destination directory (persisted in SharedPreferences)
 * and triggers MediaScannerConnection so transferred media & files immediately appear in
 * the TV's native file explorer and video players.
 */
public final class FileVaultAndroid {

    private static final String PREF_NAME = "omnideck_vault_prefs";
    private static final String KEY_FOLDER = "vault_folder_key";

    public static final String FOLDER_DOWNLOAD = "Download";
    public static final String FOLDER_OMNIDECK = "Download/OmniDeck";
    public static final String FOLDER_MOVIES = "Movies";

    public static class VaultItem {
        public final String name;
        public final long sizeBytes;
        public final long lastModified;
        public final String mimeType;
        public final boolean isApk;
        public final boolean isMedia;

        public VaultItem(String name, long sizeBytes, long lastModified, String mimeType, boolean isApk, boolean isMedia) {
            this.name = name;
            this.sizeBytes = sizeBytes;
            this.lastModified = lastModified;
            this.mimeType = mimeType;
            this.isApk = isApk;
            this.isMedia = isMedia;
        }

        public String getFormattedSize() {
            if (sizeBytes < 1024) return sizeBytes + " B";
            if (sizeBytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", sizeBytes / 1024.0);
            if (sizeBytes < 1024 * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0));
            return String.format(java.util.Locale.US, "%.2f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    private final Context context;
    private final SharedPreferences prefs;

    public FileVaultAndroid(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        ensureDirectoryExists();
    }

    public synchronized String getVaultFolderKey() {
        return prefs.getString(KEY_FOLDER, FOLDER_DOWNLOAD);
    }

    public synchronized void setVaultFolderKey(String folderKey) {
        prefs.edit().putString(KEY_FOLDER, folderKey).apply();
        ensureDirectoryExists();
    }

    public synchronized String cycleVaultFolder() {
        String current = getVaultFolderKey();
        String next;
        if (FOLDER_DOWNLOAD.equals(current)) {
            next = FOLDER_OMNIDECK;
        } else if (FOLDER_OMNIDECK.equals(current)) {
            next = FOLDER_MOVIES;
        } else {
            next = FOLDER_DOWNLOAD;
        }
        setVaultFolderKey(next);
        return next;
    }

    public synchronized File getVaultDirectory() {
        String folderKey = getVaultFolderKey();
        File root = Environment.getExternalStorageDirectory();
        File dir = new File(root, folderKey);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private void ensureDirectoryExists() {
        File dir = getVaultDirectory();
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public void scanMediaFile(File file) {
        if (file == null || !file.exists()) return;
        try {
            MediaScannerConnection.scanFile(
                    context,
                    new String[]{ file.getAbsolutePath() },
                    null,
                    (path, uri) -> {}
            );
        } catch (Exception ignored) {}
    }

    public List<VaultItem> listItems() {
        List<VaultItem> items = new ArrayList<>();
        File vaultDir = getVaultDirectory();
        File[] files = vaultDir.listFiles();
        if (files == null) return items;

        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        for (File f : files) {
            if (f.isDirectory() || f.getName().startsWith(".") || f.getName().endsWith(".part")) {
                continue;
            }
            String name = f.getName();
            String mime = getMimeType(name);
            boolean isApk = name.toLowerCase().endsWith(".apk");
            boolean isMedia = mime.startsWith("video/") || mime.startsWith("audio/") || mime.startsWith("image/");

            items.add(new VaultItem(name, f.length(), f.lastModified(), mime, isApk, isMedia));
        }

        return items;
    }

    public boolean deleteItem(String relativePath) {
        File vaultDir = getVaultDirectory();
        File target = PathJail.resolveSafe(vaultDir, relativePath);
        if (target != null && target.exists() && target.isFile()) {
            boolean deleted = target.delete();
            if (deleted) {
                scanMediaFile(target);
            }
            return deleted;
        }
        return false;
    }

    public boolean openItem(String relativePath) {
        File vaultDir = getVaultDirectory();
        File target = PathJail.resolveSafe(vaultDir, relativePath);
        if (target == null || !target.exists()) {
            Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show();
            return false;
        }

        try {
            Uri contentUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    target
            );

            String mime = getMimeType(target.getName());
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException anf) {
            Toast.makeText(context, "No app available to open this file", Toast.LENGTH_LONG).show();
            return false;
        } catch (Exception e) {
            Toast.makeText(context, "Error opening file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    public static String getMimeType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) {
            String ext = fileName.substring(dot + 1).toLowerCase();
            if ("apk".equals(ext)) return "application/vnd.android.package-archive";
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }
}
