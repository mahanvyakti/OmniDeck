package com.mahanapps.omnideck.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.mahanapps.omnideck.R;
import com.mahanapps.omnideck.core.DownloadTicketStore;
import com.mahanapps.omnideck.core.HttpServerCore;
import com.mahanapps.omnideck.core.PairingManager;
import com.mahanapps.omnideck.core.PathJail;
import com.mahanapps.omnideck.core.StorageGuard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * Foreground service hosting the pure-Java OmniDeck HTTP server.
 * Tracks local IP and multi-device pairings across Wi-Fi and Hotspot connections.
 */
public final class OmniDeckService extends Service implements HttpServerCore.ServerBackend {

    private static final String CHANNEL_ID = "omnideck_service_channel";
    private static final int NOTIFICATION_ID = 1001;
    public static final int HTTP_PORT = 8080;

    public interface OnLiveActionListener {
        void onLiveAction(String sender, String action, String detail);
    }

    private static volatile OmniDeckService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface RemoteActionListener {
        void onRemoteSpeedTestCompleted(String jsonResult);
        void onRemoteRamPurged(int count);
    }

    private volatile RemoteActionListener remoteActionListener;

    public void setRemoteActionListener(RemoteActionListener l) {
        this.remoteActionListener = l;
    }

    public static OmniDeckService getInstance() {
        return instance;
    }

    private final java.util.List<OnLiveActionListener> liveActionListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addLiveActionListener(OnLiveActionListener l) {
        if (l != null && !liveActionListeners.contains(l)) {
            liveActionListeners.add(l);
        }
    }

    public void removeLiveActionListener(OnLiveActionListener l) {
        liveActionListeners.remove(l);
    }

    public void notifyLiveAction(String sender, String action, String detail) {
        for (OnLiveActionListener l : liveActionListeners) {
            try {
                l.onLiveAction(sender, action, detail);
            } catch (Exception ignored) {}
        }
    }

    private SystemDoctorAndroid systemDoctor;
    private FileVaultAndroid fileVault;
    private NoteBoardStore noteBoard;
    private PairingManager pairingManager;
    private DownloadTicketStore ticketStore;
    private StorageGuard storageGuard;
    private HttpServerCore serverCore;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private String currentIpAddress = "127.0.0.1";

    public static final int HISTORY_BUFFER_SIZE = 40;
    private final float[] ramHistory = new float[HISTORY_BUFFER_SIZE];
    private int ramWriteIndex = 0;
    private final float[] netHistory = new float[HISTORY_BUFFER_SIZE];
    private int netWriteIndex = 0;
    private java.util.concurrent.ScheduledExecutorService telemetryScheduler;

    public SystemDoctorAndroid getSystemDoctor() { return systemDoctor; }
    public FileVaultAndroid getFileVault() { return fileVault; }
    public NoteBoardStore getNoteBoard() { return noteBoard; }
    public PairingManager getPairingManager() { return pairingManager; }
    public DownloadTicketStore getTicketStore() { return ticketStore; }
    public StorageGuard getStorageGuard() { return storageGuard; }
    public String getCurrentIpAddress() { return currentIpAddress; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        systemDoctor = new SystemDoctorAndroid(this);
        fileVault = new FileVaultAndroid(this);
        noteBoard = new NoteBoardStore(this);
        pairingManager = new PairingManager();
        ticketStore = new DownloadTicketStore();

        storageGuard = new StorageGuard(new StorageGuard.FreeSpaceSupplier() {
            @Override
            public long getTotalBytes() {
                return fileVault.getVaultDirectory().getTotalSpace();
            }

            @Override
            public long getAvailableBytes() {
                return fileVault.getVaultDirectory().getUsableSpace();
            }
        });

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Initializing OmniDeck..."));

        updateIpAddress();
        registerNetworkTracker();
        startServer();

        telemetryScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        telemetryScheduler.scheduleWithFixedDelay(this::sampleTelemetryBackground, 1, 4, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void sampleTelemetryBackground() {
        if (systemDoctor == null) return;
        try {
            SystemDoctorAndroid.RamInfo ram = systemDoctor.readRamInfo();
            synchronized (ramHistory) {
                ramHistory[ramWriteIndex] = (float) ram.getUsedPercent();
                ramWriteIndex = (ramWriteIndex + 1) % HISTORY_BUFFER_SIZE;
            }

            SystemDoctorAndroid.NetworkStats net = systemDoctor.readNetworkThroughput();
            float kb = (float) (net.rxKbPerSec + net.txKbPerSec);
            synchronized (netHistory) {
                netHistory[netWriteIndex] = kb;
                netWriteIndex = (netWriteIndex + 1) % HISTORY_BUFFER_SIZE;
            }

            if (pairingManager != null) {
                pairingManager.purgeInactiveDevices();
            }
        } catch (Throwable ignored) {}
    }

    public void getRamHistory(float[] outArray, int[] outIndex) {
        synchronized (ramHistory) {
            System.arraycopy(ramHistory, 0, outArray, 0, HISTORY_BUFFER_SIZE);
            outIndex[0] = ramWriteIndex;
        }
    }

    public void getNetHistory(float[] outArray, int[] outIndex) {
        synchronized (netHistory) {
            System.arraycopy(netHistory, 0, outArray, 0, HISTORY_BUFFER_SIZE);
            outIndex[0] = netWriteIndex;
        }
    }

    public void disconnectAllDevices() {
        if (pairingManager != null) {
            pairingManager.revokeAll();
            notifyLiveAction("TV Remote", "DISCONNECT_ALL", "Disconnected all devices from TV");
        }
    }

    @Override
    public boolean clearNotes() {
        if (noteBoard != null) {
            noteBoard.clearHistory();
            return true;
        }
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        updateIpAddress();
        updateNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (telemetryScheduler != null) {
            telemetryScheduler.shutdownNow();
            telemetryScheduler = null;
        }
        stopServer();
        unregisterNetworkTracker();
        instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startServer() {
        try {
            serverCore = new HttpServerCore(HTTP_PORT, pairingManager, ticketStore, storageGuard, this);
            serverCore.start();
            updateNotification();
        } catch (Exception e) {
            updateNotification("Error starting HTTP server: " + e.getMessage());
        }
    }

    private void stopServer() {
        if (serverCore != null) {
            serverCore.stop();
            serverCore = null;
        }
    }

    public void updateIpAddress() {
        String detected = findLocalIpAddress();
        if (detected != null && !detected.isEmpty()) {
            currentIpAddress = detected;
        }
    }

    private String findLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : interfaces) {
                if (nif.isLoopback() || !nif.isUp()) continue;
                String name = nif.getName().toLowerCase();
                if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")) {
                    for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
            for (NetworkInterface nif : interfaces) {
                if (nif.isLoopback() || !nif.isUp()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private void registerNetworkTracker() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    updateIpAddress();
                    updateNotification();
                }

                @Override
                public void onLost(Network network) {
                    updateIpAddress();
                    updateNotification();
                }
            };
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
        }
    }

    private void unregisterNetworkTracker() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
            networkCallback = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.notification_channel_desc));
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String statusText) {
        Intent notificationIntent = new Intent(this, OmniDeckActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(statusText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        String msg = "Companion URL: http://" + currentIpAddress + ":" + HTTP_PORT;
        updateNotification(msg);
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    // --- HttpServerCore.ServerBackend Implementation ---

    @Override
    public String getStatusJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("tvModel", Build.MANUFACTURER + " " + Build.MODEL);
            root.put("androidVersion", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            root.put("ip", currentIpAddress);
            root.put("port", HTTP_PORT);
            root.put("creator", "Mahan Vyakti");

            // RAM
            SystemDoctorAndroid.RamInfo ram = systemDoctor.readRamInfo();
            JSONObject ramObj = new JSONObject();
            ramObj.put("totalKb", ram.totalKb);
            ramObj.put("availableKb", ram.availableKb);
            ramObj.put("usedPercent", ram.getUsedPercent());
            root.put("ram", ramObj);

            // Storage Breakdown
            SystemDoctorAndroid.StorageBreakdown sb = systemDoctor.getStorageBreakdown();
            JSONObject storageObj = new JSONObject();
            storageObj.put("totalBytes", sb.totalBytes);
            storageObj.put("availableBytes", sb.availableBytes);
            storageObj.put("appsBytes", sb.appsBytes);
            storageObj.put("appDataBytes", sb.appDataBytes);
            storageObj.put("mediaBytes", sb.mediaBytes);
            storageObj.put("downloadsBytes", sb.downloadsBytes);
            storageObj.put("systemBytes", sb.systemBytes);
            storageObj.put("floorBytes", storageGuard.getMinimumFloorBytes());
            storageObj.put("usableUploadBytes", storageGuard.getUsableUploadBytes());
            root.put("storage", storageObj);

            // Live Network Throughput
            SystemDoctorAndroid.NetworkStats net = systemDoctor.readNetworkThroughput();
            JSONObject netObj = new JSONObject();
            netObj.put("rxKbPerSec", Math.round(net.rxKbPerSec * 10) / 10.0);
            netObj.put("txKbPerSec", Math.round(net.txKbPerSec * 10) / 10.0);
            root.put("network", netObj);

            // Multi-device paired devices list
            JSONArray devArr = new JSONArray();
            for (PairingManager.PairedDevice d : pairingManager.getPairedDevices()) {
                JSONObject dObj = new JSONObject();
                dObj.put("tokenHash", d.tokenHash);
                dObj.put("label", d.label);
                dObj.put("hostname", d.hostname);
                dObj.put("model", d.model);
                dObj.put("initials", d.getInitials());
                dObj.put("osType", d.osType);
                dObj.put("browser", d.browser);
                dObj.put("ip", d.ipAddress);
                dObj.put("connectedSec", d.getConnectedDurationSeconds());
                devArr.put(dObj);
            }
            root.put("pairedDevices", devArr);

            root.put("vaultFolder", fileVault.getVaultFolderKey());
            root.put("animationScale", systemDoctor.getAnimationScale());
            return root.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    public boolean disconnectDevice(String tokenHash) {
        if (pairingManager == null || tokenHash == null) return false;
        boolean removed = pairingManager.revokeDeviceByHash(tokenHash);
        if (removed) {
            notifyLiveAction("TV Remote", "DISCONNECT", "Disconnected device from TV");
        }
        return removed;
    }

    public String cycleVaultFolder() {
        if (fileVault == null) return FileVaultAndroid.FOLDER_DOWNLOAD;
        String next = fileVault.cycleVaultFolder();
        notifyLiveAction("TV Remote", "VAULT_FOLDER", "Storage path: /sdcard/" + next);
        return next;
    }

    @Override
    public void onClientAction(String clientIp, String action, String detail) {
        String sender = resolveDeviceLabel(clientIp);
        notifyLiveAction(sender, action, detail);
    }

    @Override
    public void disconnectAll() {
        disconnectAllDevices();
    }

    private String resolveDeviceLabel(String clientIp) {
        if (clientIp != null && pairingManager != null) {
            for (PairingManager.PairedDevice d : pairingManager.getPairedDevices()) {
                if (clientIp.equals(d.ipAddress)) {
                    return d.label;
                }
            }
            return "Companion (" + clientIp + ")";
        }
        return "Companion Device";
    }

    @Override
    public String purgeRamJson() {
        List<String> purged = systemDoctor.purgeBackgroundApps();
        SystemDoctorAndroid.RamInfo ram = systemDoctor.readRamInfo();
        if (remoteActionListener != null) {
            final int count = purged.size();
            mainHandler.post(() -> {
                if (remoteActionListener != null) {
                    remoteActionListener.onRemoteRamPurged(count);
                }
            });
        }
        try {
            JSONObject root = new JSONObject();
            root.put("purgedCount", purged.size());
            root.put("purgedPackages", new JSONArray(purged));

            JSONObject ramObj = new JSONObject();
            ramObj.put("totalKb", ram.totalKb);
            ramObj.put("availableKb", ram.availableKb);
            ramObj.put("usedPercent", ram.getUsedPercent());
            root.put("ram", ramObj);
            return root.toString();
        } catch (Exception e) {
            return "{\"purgedCount\":" + purged.size() + "}";
        }
    }

    @Override
    public String listFilesJson(String dirKey) {
        List<FileVaultAndroid.VaultItem> items = fileVault.listItems();
        try {
            JSONArray arr = new JSONArray();
            for (FileVaultAndroid.VaultItem item : items) {
                JSONObject obj = new JSONObject();
                obj.put("name", item.name);
                obj.put("sizeBytes", item.sizeBytes);
                obj.put("formattedSize", item.getFormattedSize());
                obj.put("lastModified", item.lastModified);
                obj.put("mimeType", item.mimeType);
                obj.put("isApk", item.isApk);
                obj.put("isMedia", item.isMedia);
                arr.put(obj);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    @Override
    public String getNotesJson() {
        try {
            JSONObject root = new JSONObject(noteBoard.getHistoryJson());
            root.put("note", noteBoard.getNote());
            return root.toString();
        } catch (Exception e) {
            return "{\"note\":\"\",\"history\":[]}";
        }
    }

    @Override
    public boolean addNote(String text) {
        return addNoteWithSender(text, null, null);
    }

    @Override
    public boolean addNoteWithSender(String text, String sender, String clientIp) {
        String resolvedSender = sender;
        String osType = "device";
        if (resolvedSender == null || resolvedSender.trim().isEmpty()) {
            if (clientIp != null) {
                for (PairingManager.PairedDevice d : pairingManager.getPairedDevices()) {
                    if (d.ipAddress.equals(clientIp)) {
                        resolvedSender = d.label;
                        osType = d.osType;
                        break;
                    }
                }
            }
        }
        if (resolvedSender == null || resolvedSender.trim().isEmpty()) {
            resolvedSender = clientIp != null ? "Companion (" + clientIp + ")" : "Living Room Device";
        }
        noteBoard.addClip(text, resolvedSender, osType);
        return true;
    }

    public interface ScreenshotProvider {
        byte[] captureScreenshotBytes(File saveToFile);
    }

    private volatile ScreenshotProvider screenshotProvider;

    public ScreenshotProvider getScreenshotProvider() {
        return screenshotProvider;
    }

    public void setScreenshotProvider(ScreenshotProvider p) {
        this.screenshotProvider = p;
    }

    @Override
    public byte[] captureScreenshot(String clientIp) {
        final String sender = resolveDeviceLabel(clientIp);
        mainHandler.post(() -> {
            try {
                Toast.makeText(getApplicationContext(),
                        "📷 Screen captured by " + sender,
                        Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        });
        return captureScreenshot();
    }

    @Override
    public byte[] captureScreenshot() {
        File ssDir = new File(android.os.Environment.getExternalStorageDirectory(), "Pictures/Screenshots");
        if (!ssDir.exists()) {
            ssDir.mkdirs();
        }
        String fname = "Screenshot_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".png";
        File saveToFile = new File(ssDir, fname);

        for (int attempt = 0; attempt < 2; attempt++) {
            // 1. First priority: OmniDeckAccessibilityService (captures ANY foreground app)
            OmniDeckAccessibilityService a11y = OmniDeckAccessibilityService.getInstance();
            if (a11y != null) {
                byte[] bytes = a11y.captureScreenshotBytes(saveToFile);
                if (bytes != null && bytes.length > 0) {
                    return bytes;
                }
            }

            // 2. Fall back to OmniDeckActivity (captures window via PixelCopy)
            if (screenshotProvider != null) {
                byte[] bytes = screenshotProvider.captureScreenshotBytes(saveToFile);
                if (bytes != null && bytes.length > 0) {
                    return bytes;
                }
            }

            if (attempt == 0) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    @Override
    public boolean typeText(String text) {
        OmniDeckAccessibilityService a11y = OmniDeckAccessibilityService.getInstance();
        if (a11y != null) {
            return a11y.typeTextIntoFocusedView(text);
        }
        return false;
    }

    @Override
    public String runSpeedTest() {
        String result = systemDoctor.runSpeedTest();
        if (remoteActionListener != null) {
            mainHandler.post(() -> {
                if (remoteActionListener != null) {
                    remoteActionListener.onRemoteSpeedTestCompleted(result);
                }
            });
        }
        return result;
    }

    @Override
    public String listAppsJson() {
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> apps = pm.queryIntentActivities(intent, 0);
        try {
            JSONArray arr = new JSONArray();
            for (ResolveInfo ri : apps) {
                if (ri.activityInfo != null) {
                    JSONObject obj = new JSONObject();
                    obj.put("pkg", ri.activityInfo.packageName);
                    obj.put("label", ri.loadLabel(pm).toString());
                    arr.put(obj);
                }
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    @Override
    public boolean launchApp(String pkg) {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public boolean setAnimationScale(float scale) {
        return systemDoctor.setAnimationScale(scale);
    }

    @Override
    public boolean performFileAction(String action, File file) {
        if ("open".equalsIgnoreCase(action) || "install".equalsIgnoreCase(action)) {
            return fileVault.openItem(file.getName());
        } else if ("delete".equalsIgnoreCase(action)) {
            return fileVault.deleteItem(file.getName());
        }
        return false;
    }

    @Override
    public File getVaultDirectory() {
        return fileVault.getVaultDirectory();
    }

    @Override
    public File resolveAllowedFile(String relPath) {
        return PathJail.resolveSafe(fileVault.getVaultDirectory(), relPath);
    }

    @Override
    public byte[] getAsset(String path) {
        String cleanPath = path;
        while (cleanPath.startsWith("/")) {
            cleanPath = cleanPath.substring(1);
        }
        if (cleanPath.isEmpty()) {
            cleanPath = "index.html";
        }
        if (!cleanPath.startsWith("web/")) {
            cleanPath = "web/" + cleanPath;
        }
        try (InputStream is = getAssets().open(cleanPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
