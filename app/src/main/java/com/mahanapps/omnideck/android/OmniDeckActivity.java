package com.mahanapps.omnideck.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mahanapps.omnideck.R;
import com.mahanapps.omnideck.core.PairingManager;
import com.mahanapps.omnideck.core.QrCode;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV Main Activity for OmniDeck.
 * High-utility, OLED-optimized living room dashboard featuring:
 * - Direct D-pad TV remote navigation with tactile focus highlights
 * - Real-time Windows Task Manager-style resource history graphs with auto-scaling
 * - Google Docs-style collaborator avatars & force disconnect management
 * - Confined vertical Shared NoteBoard history with sender device attribution
 * - Multi-metric network speed test (Download, Upload, Latency, Jitter)
 * - Real-time on-screen banner reflecting companion actions instantly
 * - Animation Scale Selector & Configurable Storage Vault folder
 * - Decoupled timers (1-sec PIN tick, 5-sec system diagnostics)
 */
public final class OmniDeckActivity extends Activity implements
        NoteBoardStore.OnNoteChangeListener,
        OmniDeckService.OnLiveActionListener,
        OmniDeckService.ScreenshotProvider {

    private static boolean sColdStartSpeedTestDone = false;
    private static final int[] AVATAR_COLORS = new int[]{0xFF7E57C2, 0xFF00A8FF, 0xFF00E676, 0xFFFFB300, 0xFFFF5252};

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Top Navigation
    private LinearLayout llTopAvatars;
    private TextView tvConnectedDevicesBadge;
    private TextView tvDeviceInfo;

    // Live Action Banner
    private LinearLayout llLiveActionBanner;
    private TextView tvLiveActionIcon;
    private TextView tvLiveActionText;

    // Card 1: RAM
    private View cardRam;
    private TextView tvRamMain;
    private Button btnPurgeRam;
    private GraphHistoryView graphRam;

    // Card 2: Network & Speed Test
    private View cardNetwork;
    private TextView tvSpeedReadout;
    private TextView tvSpeedSubtext;
    private Button btnSpeedTest;
    private GraphHistoryView graphNetwork;

    // Card 3: Storage
    private View cardStorage;
    private DonutChartView donutStorage;
    private TextView tvStFree;
    private TextView tvStApps;
    private TextView tvStMedia;
    private TextView tvStDownloads;
    private TextView tvStSys;

    // Card 4: Clipboard & History
    private View cardNotes;
    private TextView tvClipSender;
    private TextView tvNotePreview;
    private Button btnCopyClipboard;
    private Button btnClearClipboard;
    private ScrollView svClipHistory;
    private LinearLayout llClipHistory;

    // Card 5: Connect Companion
    private ImageView ivQrCode;
    private TextView tvPinDisplay;
    private TextView tvPinExpiry;
    private Button btnTogglePin;
    private Button btnNewPin;
    private boolean isPinVisible = false;
    private TextView tvQrUrl;

    // Card 6: System Controls & Vault
    private View cardSystemControls;
    private Button btnAnimOff;
    private Button btnAnimSnappy;
    private Button btnAnimNormal;
    private Button btnVaultFolder;
    private TextView tvVaultTip;
    private Button btnExitApp;

    private String lastQrUrl = "";
    private boolean noteListenerRegistered = false;
    private boolean liveListenerRegistered = false;

    // Decoupled Runnables
    // 1. PIN Timer: updates every 1000ms
    private final Runnable pinTimerRunnable = new Runnable() {
        @Override
        public void run() {
            updatePinState();
            mainHandler.postDelayed(this, 1000);
        }
    };

    // 2. Diagnostics: updates every 5000ms
    private final Runnable diagnosticsRunnable = new Runnable() {
        @Override
        public void run() {
            refreshSystemDiagnostics();
            mainHandler.postDelayed(this, 5000);
        }
    };

    private final Runnable hideBannerRunnable = new Runnable() {
        @Override
        public void run() {
            if (llLiveActionBanner != null && llLiveActionBanner.getVisibility() == View.VISIBLE) {
                llLiveActionBanner.animate().alpha(0f).setDuration(250).withEndAction(() -> {
                    llLiveActionBanner.setVisibility(View.GONE);
                }).start();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // SafeMode crash loop guard
        SafeMode safeMode = new SafeMode(this);
        if (safeMode.isSafeModeActive()) {
            Toast.makeText(this, "OmniDeck recovered from prior crash.", Toast.LENGTH_LONG).show();
        }

        // Start background service
        Intent serviceIntent = new Intent(this, OmniDeckService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        setContentView(R.layout.activity_omnideck);
        initViews();
        setupListeners();
        setupSystemControls();

        // Initial setup for history graphs
        if (graphRam != null) {
            graphRam.setLineColor(0xFF00A8FF); // Accent Blue
            graphRam.setMaxValue(100f);
            graphRam.setUnit("%");
        }
        if (graphNetwork != null) {
            graphNetwork.setLineColor(0xFF00E676); // Accent Green
            graphNetwork.setAutoScale(true, 20f);
            graphNetwork.setMaxValue(200f);
            graphNetwork.setUnit("KB/s");
        }

        // Cold start speed test trigger
        if (!sColdStartSpeedTestDone) {
            sColdStartSpeedTestDone = true;
            runSpeedCheckAsync();
        }
    }

    private void initViews() {
        llTopAvatars = findViewById(R.id.ll_top_avatars);
        tvConnectedDevicesBadge = findViewById(R.id.tv_connected_devices_badge);
        tvDeviceInfo = findViewById(R.id.tv_device_info);

        llLiveActionBanner = findViewById(R.id.ll_live_action_banner);
        tvLiveActionIcon = findViewById(R.id.tv_live_action_icon);
        tvLiveActionText = findViewById(R.id.tv_live_action_text);

        cardRam = findViewById(R.id.card_ram);
        tvRamMain = findViewById(R.id.tv_ram_main);
        btnPurgeRam = findViewById(R.id.btn_purge_ram);
        graphRam = findViewById(R.id.graph_ram);

        cardNetwork = findViewById(R.id.card_network);
        tvSpeedReadout = findViewById(R.id.tv_speed_readout);
        tvSpeedSubtext = findViewById(R.id.tv_speed_subtext);
        btnSpeedTest = findViewById(R.id.btn_speed_test);
        graphNetwork = findViewById(R.id.graph_network);

        cardStorage = findViewById(R.id.card_storage);
        donutStorage = findViewById(R.id.donut_storage);
        tvStFree = findViewById(R.id.tv_st_free);
        tvStApps = findViewById(R.id.tv_st_apps);
        tvStMedia = findViewById(R.id.tv_st_media);
        tvStDownloads = findViewById(R.id.tv_st_downloads);
        tvStSys = findViewById(R.id.tv_st_sys);

        cardNotes = findViewById(R.id.card_notes);
        tvClipSender = findViewById(R.id.tv_clip_sender);
        tvNotePreview = findViewById(R.id.tv_note_preview);
        btnCopyClipboard = findViewById(R.id.btn_copy_clipboard);
        btnClearClipboard = findViewById(R.id.btn_clear_clipboard);
        svClipHistory = findViewById(R.id.sv_clip_history);
        llClipHistory = findViewById(R.id.ll_clip_history);

        ivQrCode = findViewById(R.id.iv_qr_code);
        tvPinDisplay = findViewById(R.id.tv_pin_display);
        tvPinExpiry = findViewById(R.id.tv_pin_expiry);
        btnTogglePin = findViewById(R.id.btn_toggle_pin);
        btnNewPin = findViewById(R.id.btn_new_pin);
        tvQrUrl = findViewById(R.id.tv_qr_url);

        cardSystemControls = findViewById(R.id.card_system_controls);
        btnAnimOff = findViewById(R.id.btn_anim_off);
        btnAnimSnappy = findViewById(R.id.btn_anim_snappy);
        btnAnimNormal = findViewById(R.id.btn_anim_normal);
        btnVaultFolder = findViewById(R.id.btn_vault_folder);
        tvVaultTip = findViewById(R.id.tv_vault_tip);
        btnExitApp = findViewById(R.id.btn_exit_app);
    }

    private void setupListeners() {
        View.OnFocusChangeListener focusAnimator = (v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.08f : 1.0f).scaleY(hasFocus ? 1.08f : 1.0f).setDuration(120).start();
        };

        if (btnPurgeRam != null) {
            btnPurgeRam.setOnClickListener(v -> purgeRam());
            btnPurgeRam.setOnFocusChangeListener(focusAnimator);
        }

        if (btnSpeedTest != null) {
            btnSpeedTest.setOnClickListener(v -> runSpeedCheckAsync());
            btnSpeedTest.setOnFocusChangeListener(focusAnimator);
        }

        if (btnCopyClipboard != null) {
            btnCopyClipboard.setOnClickListener(v -> copyNoteToClipboard());
            btnCopyClipboard.setOnFocusChangeListener(focusAnimator);
        }

        if (btnClearClipboard != null) {
            btnClearClipboard.setOnClickListener(v -> clearClipboardHistory());
            btnClearClipboard.setOnFocusChangeListener(focusAnimator);
        }

        if (btnTogglePin != null) {
            btnTogglePin.setOnClickListener(v -> {
                isPinVisible = !isPinVisible;
                btnTogglePin.setText(isPinVisible ? "Hide" : "Show");
                refreshPinDisplay();
            });
            btnTogglePin.setOnFocusChangeListener(focusAnimator);
        }

        if (btnNewPin != null) {
            btnNewPin.setOnClickListener(v -> generateNewPin());
            btnNewPin.setOnFocusChangeListener(focusAnimator);
        }

        if (tvConnectedDevicesBadge != null) {
            int[][] states = new int[][] {
                new int[] { android.R.attr.state_focused },
                new int[] {}
            };
            int[] colors = new int[] {
                0xFF000000, // Focused: Bold Black on bright Cyan
                0xFF00E5FF  // Default: Electric Cyan on Deep Navy
            };
            tvConnectedDevicesBadge.setTextColor(new android.content.res.ColorStateList(states, colors));
            tvConnectedDevicesBadge.setOnClickListener(v -> showConnectedDevicesDialog(null));
            tvConnectedDevicesBadge.setOnFocusChangeListener(focusAnimator);
        }
    }

    private void setupSystemControls() {
        View.OnFocusChangeListener focusAnimator = (v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.08f : 1.0f).scaleY(hasFocus ? 1.08f : 1.0f).setDuration(120).start();
        };

        if (btnAnimOff != null) {
            btnAnimOff.setOnClickListener(v -> setAnimationScale(0.0f));
            btnAnimOff.setOnFocusChangeListener(focusAnimator);
        }
        if (btnAnimSnappy != null) {
            btnAnimSnappy.setOnClickListener(v -> setAnimationScale(0.5f));
            btnAnimSnappy.setOnFocusChangeListener(focusAnimator);
        }
        if (btnAnimNormal != null) {
            btnAnimNormal.setOnClickListener(v -> setAnimationScale(1.0f));
            btnAnimNormal.setOnFocusChangeListener(focusAnimator);
        }
        if (btnVaultFolder != null) {
            btnVaultFolder.setOnClickListener(v -> cycleVaultFolder());
            btnVaultFolder.setOnFocusChangeListener(focusAnimator);
        }
        if (btnExitApp != null) {
            btnExitApp.setOnClickListener(v -> exitAppCleanly());
            btnExitApp.setOnFocusChangeListener(focusAnimator);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAndRegisterListeners();

        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc != null) {
            svc.setRemoteActionListener(new OmniDeckService.RemoteActionListener() {
                @Override
                public void onRemoteSpeedTestCompleted(String jsonResult) {
                    mainHandler.post(() -> applySpeedTestResult(jsonResult));
                }

                @Override
                public void onRemoteRamPurged(int count) {
                    mainHandler.post(() -> {
                        refreshSystemDiagnostics();
                        Toast.makeText(OmniDeckActivity.this, "Companion freed RAM (" + count + " processes stopped)", Toast.LENGTH_SHORT).show();
                    });
                }
            });
        }

        // Start decoupled periodic loops
        mainHandler.post(pinTimerRunnable);
        mainHandler.post(diagnosticsRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(pinTimerRunnable);
        mainHandler.removeCallbacks(diagnosticsRunnable);
        mainHandler.removeCallbacks(hideBannerRunnable);

        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc != null) {
            svc.setRemoteActionListener(null);
            if (svc.getNoteBoard() != null) {
                svc.getNoteBoard().removeListener(this);
                noteListenerRegistered = false;
            }
            svc.removeLiveActionListener(this);
            liveListenerRegistered = false;
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc != null && svc.getScreenshotProvider() == this) {
            svc.setScreenshotProvider(null);
        }
        super.onDestroy();
    }

    private void checkAndRegisterListeners() {
        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc == null) return;

        if (!noteListenerRegistered && svc.getNoteBoard() != null) {
            svc.getNoteBoard().addListener(this);
            onNoteChanged(svc.getNoteBoard().getActiveClip());
            noteListenerRegistered = true;
        }

        if (!liveListenerRegistered) {
            svc.addLiveActionListener(this);
            liveListenerRegistered = true;
        }

        svc.setScreenshotProvider(this);
    }

    // --- Live Companion Action Reflection (Requirement 7) ---

    @Override
    public void onLiveAction(String sender, String action, String detail) {
        mainHandler.post(() -> {
            showLiveActionBanner(sender, action, detail);
            flashCardHighlight(action);
            refreshSystemDiagnostics();
        });
    }

    private void showLiveActionBanner(String sender, String action, String detail) {
        if (llLiveActionBanner == null) return;

        String icon = "⚡";
        if ("CLIPBOARD".equals(action)) icon = "📋";
        else if ("SPEED_TEST".equals(action)) icon = "🚀";
        else if ("FILE_UPLOAD".equals(action)) icon = "📁";
        else if ("SCREENSHOT".equals(action)) icon = "📸";
        else if ("PAIR".equals(action)) icon = "📱";
        else if ("ANIMATION".equals(action)) icon = "✨";
        else if ("FILE_ACTION".equals(action)) icon = "⚙️";

        if (tvLiveActionIcon != null) tvLiveActionIcon.setText(icon);
        if (tvLiveActionText != null) {
            tvLiveActionText.setText("[" + sender + "] " + detail);
        }

        llLiveActionBanner.setVisibility(View.VISIBLE);
        llLiveActionBanner.setAlpha(0f);
        llLiveActionBanner.animate().alpha(1.0f).setDuration(180).start();

        mainHandler.removeCallbacks(hideBannerRunnable);
        mainHandler.postDelayed(hideBannerRunnable, 4500);
    }

    private void flashCardHighlight(String action) {
        View target = null;
        if ("PURGE_RAM".equals(action)) target = cardRam;
        else if ("SPEED_TEST".equals(action)) target = cardNetwork;
        else if ("CLIPBOARD".equals(action)) target = cardNotes;
        else if ("FILE_UPLOAD".equals(action) || "ANIMATION".equals(action) || "VAULT_FOLDER".equals(action)) {
            target = cardSystemControls;
        }

        if (target != null) {
            final View fTarget = target;
            fTarget.animate().scaleX(1.03f).scaleY(1.03f).setDuration(140).withEndAction(() -> {
                fTarget.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140).start();
            }).start();
        }
    }

    // --- High-Performance TV Screenshot Engine (Task 1) ---

    private static volatile byte[] lastValidScreenshotBytes = null;

    @Override
    public byte[] captureScreenshotBytes(java.io.File saveToFile) {
        final View decorView = getWindow().getDecorView();
        final int w = decorView.getWidth() > 0 ? decorView.getWidth() : 1920;
        final int h = decorView.getHeight() > 0 ? decorView.getHeight() : 1080;
        final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final int[] copyResult = new int[]{-1};

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                android.view.PixelCopy.request(getWindow(), bmp, copyResultCode -> {
                    copyResult[0] = copyResultCode;
                    latch.countDown();
                }, new Handler(Looper.getMainLooper()));

                latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {}
        }

        Bitmap finalBmp = (copyResult[0] == android.view.PixelCopy.SUCCESS) ? bmp : null;
        if (finalBmp == null) {
            // Software Canvas Fallback dispatched to UI thread
            final java.util.concurrent.CountDownLatch drawLatch = new java.util.concurrent.CountDownLatch(1);
            final Bitmap[] canvasBmp = new Bitmap[1];
            mainHandler.post(() -> {
                try {
                    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(b);
                    decorView.draw(c);
                    canvasBmp[0] = b;
                } catch (Throwable t) {
                    android.util.Log.e("OmniDeck", "decorView.draw failed: " + t);
                } finally {
                    drawLatch.countDown();
                }
            });
            try {
                drawLatch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}
            finalBmp = canvasBmp[0];
        }

        byte[] resultBytes = null;
        if (finalBmp != null) {
            try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                finalBmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
                resultBytes = baos.toByteArray();
                lastValidScreenshotBytes = resultBytes;
            } catch (Exception ignored) {}
        }

        if (resultBytes == null && lastValidScreenshotBytes != null) {
            resultBytes = lastValidScreenshotBytes;
        }

        if (resultBytes != null && saveToFile != null) {
            try {
                if (saveToFile.getParentFile() != null) {
                    saveToFile.getParentFile().mkdirs();
                }
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(saveToFile)) {
                    fos.write(resultBytes);
                    fos.flush();
                }
                android.media.MediaScannerConnection.scanFile(
                        this,
                        new String[]{saveToFile.getAbsolutePath()},
                        new String[]{"image/png"},
                        null
                );
            } catch (Exception ignored) {}
        }

        return resultBytes;
    }

    // --- Decoupled 1-second PIN Logic ---

    private void updatePinState() {
        checkAndRegisterListeners();
        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc == null) return;

        PairingManager pm = svc.getPairingManager();
        if (pm == null) return;

        if (!pm.isPairingWindowOpen()) {
            pm.openPairingWindow();
        }

        String pin = pm.getCurrentPin();
        long remSec = pm.getPinRemainingSeconds();

        if (pin != null && pin.length() == 6) {
            if (isPinVisible) {
                tvPinDisplay.setText(pin.substring(0, 3) + " " + pin.substring(3));
            } else {
                tvPinDisplay.setText("••• •••");
            }
            android.util.Log.i("OmniDeck", "Active PIN: " + pin);
        } else {
            tvPinDisplay.setText("--- ---");
        }

        long min = remSec / 60;
        long sec = remSec % 60;
        tvPinExpiry.setText(String.format(Locale.US, "PIN valid for %d:%02d", min, sec));

        // Update Companion QR URL if IP has changed
        String ip = svc.getCurrentIpAddress();
        String url = "http://" + ip + ":" + OmniDeckService.HTTP_PORT;
        if (!url.equals(lastQrUrl)) {
            lastQrUrl = url;
            tvDeviceInfo.setText(ip + ":" + OmniDeckService.HTTP_PORT);
            tvQrUrl.setText(url);
            renderQrCode(url);
        }
    }

    private void refreshPinDisplay() {
        updatePinState();
    }

    private void generateNewPin() {
        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc != null && svc.getPairingManager() != null) {
            svc.getPairingManager().openPairingWindow();
            updatePinState();
            Toast.makeText(this, "Generated fresh connection PIN", Toast.LENGTH_SHORT).show();
        }
    }

    // --- Pure Java QR Code Rendering with Guaranteed 4-Module Quiet Zone ---

    private void renderQrCode(String url) {
        if (ivQrCode == null) return;
        executor.execute(() -> {
            try {
                QrCode qr = QrCode.encodeText(url, QrCode.Ecc.MEDIUM);
                int qrSize = qr.size;
                int quietZone = 4; // Standard ISO/IEC quiet zone
                int totalModules = qrSize + (quietZone * 2);
                int targetPx = 320;
                int scale = Math.max(1, targetPx / totalModules);
                int bitmapSize = totalModules * scale;

                Bitmap bmp = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                canvas.drawColor(Color.WHITE);

                Paint paint = new Paint();
                paint.setColor(Color.BLACK);
                paint.setStyle(Paint.Style.FILL);

                for (int y = 0; y < qrSize; y++) {
                    for (int x = 0; x < qrSize; x++) {
                        if (qr.getModule(x, y)) {
                            float left = (x + quietZone) * scale;
                            float top = (y + quietZone) * scale;
                            canvas.drawRect(left, top, left + scale, top + scale, paint);
                        }
                    }
                }

                mainHandler.post(() -> ivQrCode.setImageBitmap(bmp));
            } catch (Exception ignored) {}
        });
    }

    // --- Decoupled 5-second System Diagnostics ---

    private void refreshSystemDiagnostics() {
        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc == null) return;

        SystemDoctorAndroid doctor = svc.getSystemDoctor();
        if (doctor == null) return;

        // 1. RAM Utilization & History Graph
        SystemDoctorAndroid.RamInfo ram = doctor.readRamInfo();
        long usedMb = (ram.totalKb - ram.availableKb) / 1024;
        int usedPct = ram.getUsedPercent();
        tvRamMain.setText(String.format(Locale.US, "%d MB used (%d%%)", usedMb, usedPct));
        if (graphRam != null) {
            float[] rSamples = new float[GraphHistoryView.BUFFER_SIZE];
            int[] rIdx = new int[1];
            svc.getRamHistory(rSamples, rIdx);
            graphRam.setHistory(rSamples, rIdx[0]);
        }

        // 2. Live Network Throughput & Graph
        SystemDoctorAndroid.NetworkStats net = doctor.readNetworkThroughput();
        float totalThroughput = net.rxKbPerSec + net.txKbPerSec;
        if (graphNetwork != null) {
            float[] nSamples = new float[GraphHistoryView.BUFFER_SIZE];
            int[] nIdx = new int[1];
            svc.getNetHistory(nSamples, nIdx);
            graphNetwork.setHistory(nSamples, nIdx[0]);
        }

        // 3. Granular & Accurate Storage Breakdown & Donut Chart
        SystemDoctorAndroid.StorageBreakdown sb = doctor.getStorageBreakdown();
        long totalApps = sb.appsBytes + sb.appDataBytes;
        if (donutStorage != null) {
            donutStorage.setStorageData(sb.totalBytes, sb.availableBytes, totalApps, sb.mediaBytes, sb.downloadsBytes, sb.systemBytes);
        }

        double gb = 1024.0 * 1024.0 * 1024.0;
        double mb = 1024.0 * 1024.0;
        if (tvStFree != null) {
            tvStFree.setText(String.format(Locale.US, "Free: %.1f GB", sb.availableBytes / gb));
        }
        if (tvStApps != null) {
            if (totalApps >= gb) {
                tvStApps.setText(String.format(Locale.US, "Apps & Data: %.1f GB", totalApps / gb));
            } else {
                tvStApps.setText(String.format(Locale.US, "Apps & Data: %.0f MB", totalApps / mb));
            }
        }
        if (tvStMedia != null) {
            tvStMedia.setText(String.format(Locale.US, "Media: %.0f MB", sb.mediaBytes / mb));
        }
        if (tvStDownloads != null) {
            tvStDownloads.setText(String.format(Locale.US, "Downloads: %.0f MB", sb.downloadsBytes / mb));
        }
        if (tvStSys != null) {
            tvStSys.setText(String.format(Locale.US, "System: %.1f GB", sb.systemBytes / gb));
        }

        // 4. Paired Collaborators & Google Docs Style Avatars (Requirement 8)
        PairingManager pm = svc.getPairingManager();
        if (pm != null) {
            List<PairingManager.PairedDevice> devices = pm.getPairedDevices();
            int count = devices.size();
            if (tvConnectedDevicesBadge != null) {
                tvConnectedDevicesBadge.setText(count + (count == 1 ? " Device Active" : " Devices Active"));
            }
            updateTopAvatars(devices);
        }

        // 5. System Controls Status
        updateAnimationScaleButtons(doctor.getAnimationScale());
        if (btnVaultFolder != null && svc.getFileVault() != null) {
            btnVaultFolder.setText("📁 /sdcard/" + svc.getFileVault().getVaultFolderKey());
        }
    }

    // --- Google Docs Style Top Navigation Collaborator Avatars (Requirement 8) ---

    private void updateTopAvatars(List<PairingManager.PairedDevice> devices) {
        if (llTopAvatars == null) return;
        llTopAvatars.removeAllViews();
        if (devices == null || devices.isEmpty()) return;

        int[] colors = new int[]{0xFF7E57C2, 0xFF00A8FF, 0xFF00E676, 0xFFFFB300, 0xFFFF5252};
        int maxAvatars = Math.min(devices.size(), 4);
        int sizePx = (int) (28 * getResources().getDisplayMetrics().density);
        int marginPx = (int) (4 * getResources().getDisplayMetrics().density);

        for (int i = 0; i < maxAvatars; i++) {
            final PairingManager.PairedDevice dev = devices.get(i);
            TextView avatar = new TextView(this);
            avatar.setText(dev.getInitials());
            avatar.setTextColor(0xFFFFFFFF);
            avatar.setTextSize(10f);
            avatar.setTypeface(null, Typeface.BOLD);
            avatar.setGravity(Gravity.CENTER);

            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            int color = colors[Math.abs(dev.label.hashCode()) % colors.length];
            gd.setColor(color);
            gd.setStroke((int) (1.5f * getResources().getDisplayMetrics().density), 0xFFFFFFFF);
            avatar.setBackground(gd);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizePx, sizePx);
            lp.setMarginEnd(marginPx);
            avatar.setLayoutParams(lp);

            avatar.setFocusable(true);
            avatar.setClickable(true);
            avatar.setOnFocusChangeListener((v, hasFocus) -> {
                v.animate().scaleX(hasFocus ? 1.2f : 1.0f).scaleY(hasFocus ? 1.2f : 1.0f).setDuration(120).start();
            });
            avatar.setOnClickListener(v -> showConnectedDevicesDialog(dev));

            llTopAvatars.addView(avatar);
        }
    }

    // --- Connected Devices Details & Force Disconnect Modal (Requirement 1, 2, 8) ---

    private void showConnectedDevicesDialog(PairingManager.PairedDevice singleDeviceOnly) {
        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc == null || svc.getPairingManager() == null) return;

        List<PairingManager.PairedDevice> list = singleDeviceOnly != null
                ? Collections.singletonList(singleDeviceOnly)
                : svc.getPairingManager().getPairedDevices();

        if (list.isEmpty()) {
            Toast.makeText(this, "No devices currently paired.", Toast.LENGTH_SHORT).show();
            return;
        }

        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (18 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_oled_bg);
        root.setPadding(pad, pad, pad, pad);
        root.setLayoutParams(new ViewGroup.LayoutParams((int) (520 * density), ViewGroup.LayoutParams.WRAP_CONTENT));

        // 1. Header Bar: Title + Active Badge + TV-Only Disconnect All
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(singleDeviceOnly != null ? "Device Details" : "Active Collaborators");
        tvTitle.setTextColor(0xFFF8FAFC);
        tvTitle.setTextSize(15f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvBadge = new TextView(this);
        tvBadge.setText(list.size() + (list.size() == 1 ? " ACTIVE" : " ACTIVE"));
        tvBadge.setTextColor(0xFF00E5FF);
        tvBadge.setTextSize(10f);
        tvBadge.setTypeface(null, Typeface.BOLD);
        tvBadge.setPadding((int) (8 * density), (int) (3 * density), (int) (8 * density), (int) (3 * density));
        tvBadge.setBackgroundResource(R.drawable.header_device_badge_bg);
        tvBadge.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        header.addView(tvTitle);
        header.addView(tvBadge);

        View.OnFocusChangeListener focusAnimator = (v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.04f : 1.0f).scaleY(hasFocus ? 1.04f : 1.0f).setDuration(120).start();
        };

        if (singleDeviceOnly == null && list.size() > 0) {
            TextView btnDisconnectAll = new TextView(this);
            btnDisconnectAll.setText("✕ Disconnect All");
            btnDisconnectAll.setTextSize(10f);
            btnDisconnectAll.setTypeface(null, Typeface.BOLD);
            btnDisconnectAll.setTextColor(getResources().getColorStateList(R.drawable.danger_btn_text_selector));
            btnDisconnectAll.setBackgroundResource(R.drawable.danger_btn_selector);
            btnDisconnectAll.setFocusable(true);
            btnDisconnectAll.setClickable(true);
            btnDisconnectAll.setGravity(Gravity.CENTER);
            btnDisconnectAll.setPadding((int) (12 * density), (int) (5 * density), (int) (12 * density), (int) (5 * density));
            LinearLayout.LayoutParams dAllParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dAllParams.leftMargin = (int) (10 * density);
            btnDisconnectAll.setLayoutParams(dAllParams);
            btnDisconnectAll.setOnFocusChangeListener(focusAnimator);
            btnDisconnectAll.setOnClickListener(v -> {
                svc.disconnectAllDevices();
                dialog.dismiss();
                Toast.makeText(this, "Disconnected all devices from TV", Toast.LENGTH_SHORT).show();
                refreshSystemDiagnostics();
            });
            header.addView(btnDisconnectAll);
        }

        root.addView(header);

        // Divider
        View divider = new View(this);
        divider.setBackgroundColor(0xFF1E293B);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (1 * density));
        divParams.setMargins(0, (int) (12 * density), 0, (int) (12 * density));
        root.addView(divider, divParams);

        // 2. Scrollable Device List
        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        scroll.setLayoutParams(scrollParams);

        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < list.size(); i++) {
            PairingManager.PairedDevice dev = list.get(i);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackgroundResource(R.drawable.device_item_card_selector);
            int cardPadH = (int) (14 * density);
            int cardPadV = (int) (10 * density);
            card.setPadding(cardPadH, cardPadV, cardPadH, cardPadV);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (i > 0) {
                cardParams.topMargin = (int) (8 * density);
            }
            card.setLayoutParams(cardParams);

            // Sleek device type glyph icon (Laptop, Phone, Browser)
            TextView iconView = new TextView(this);
            int iconSize = (int) (34 * density);
            LinearLayout.LayoutParams icParams = new LinearLayout.LayoutParams(iconSize, iconSize);
            icParams.rightMargin = (int) (12 * density);
            iconView.setLayoutParams(icParams);
            iconView.setGravity(Gravity.CENTER);

            String icon = "🌐";
            String osLower = dev.osType != null ? dev.osType.toLowerCase() : "";
            String labelLower = dev.label != null ? dev.label.toLowerCase() : "";
            if (osLower.contains("win") || osLower.contains("mac") || labelLower.contains("windows") || labelLower.contains("macbook") || labelLower.contains("pc")) {
                icon = "💻";
            } else if (osLower.contains("android") || osLower.contains("ios") || labelLower.contains("phone") || labelLower.contains("s25") || labelLower.contains("galaxy") || labelLower.contains("pixel") || labelLower.contains("oneplus")) {
                icon = "📱";
            }
            iconView.setText(icon);
            iconView.setTextSize(15f);

            GradientDrawable icBg = new GradientDrawable();
            icBg.setShape(GradientDrawable.OVAL);
            icBg.setColor(0xFF131D2F);
            icBg.setStroke((int) (1 * density), 0xFF1E2E48);
            iconView.setBackground(icBg);

            // Info column
            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);
            infoCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

            String displayName = (dev.label != null && !dev.label.isEmpty() && !"K".equals(dev.label))
                    ? dev.label
                    : ((dev.model != null && !dev.model.isEmpty() && !"K".equals(dev.model)) ? dev.model : "Companion Device");

            TextView tvName = new TextView(this);
            tvName.setText(displayName);
            tvName.setTextColor(0xFFF8FAFC);
            tvName.setTextSize(13f);
            tvName.setTypeface(null, Typeface.BOLD);

            TextView tvMeta = new TextView(this);
            StringBuilder sbMeta = new StringBuilder();
            if (dev.osType != null && !dev.osType.isEmpty() && !"device".equalsIgnoreCase(dev.osType)) {
                sbMeta.append(dev.osType);
            }
            if (dev.browser != null && !dev.browser.isEmpty()) {
                if (sbMeta.length() > 0) sbMeta.append(" · ");
                sbMeta.append(dev.browser);
            }
            if (sbMeta.length() > 0) sbMeta.append(" · ");
            sbMeta.append(dev.ipAddress);
            long sec = dev.getConnectedDurationSeconds();
            String dur = sec < 60 ? (sec + "s ago") : ((sec / 60) + "m ago");
            sbMeta.append(" · ").append(dur);

            tvMeta.setText(sbMeta.toString());
            tvMeta.setTextColor(0xFF94A3B8);
            tvMeta.setTextSize(10f);
            tvMeta.setPadding(0, (int) (2 * density), 0, 0);

            infoCol.addView(tvName);
            infoCol.addView(tvMeta);

            // Disconnect button (Pixel-perfect TextView pill)
            TextView btnDisconnect = new TextView(this);
            btnDisconnect.setText("Disconnect");
            btnDisconnect.setTextSize(10.5f);
            btnDisconnect.setTypeface(null, Typeface.BOLD);
            btnDisconnect.setTextColor(getResources().getColorStateList(R.drawable.danger_btn_text_selector));
            btnDisconnect.setBackgroundResource(R.drawable.danger_btn_selector);
            btnDisconnect.setFocusable(true);
            btnDisconnect.setClickable(true);
            btnDisconnect.setGravity(Gravity.CENTER);
            btnDisconnect.setPadding((int) (14 * density), (int) (6 * density), (int) (14 * density), (int) (6 * density));
            btnDisconnect.setOnFocusChangeListener(focusAnimator);

            final String disconnectedDeviceName = displayName;
            btnDisconnect.setOnClickListener(v -> {
                svc.disconnectDevice(dev.tokenHash);
                dialog.dismiss();
                Toast.makeText(this, "Disconnected " + disconnectedDeviceName, Toast.LENGTH_SHORT).show();
                refreshSystemDiagnostics();
            });

            card.addView(iconView);
            card.addView(infoCol);
            card.addView(btnDisconnect);
            listContainer.addView(card);
        }

        scroll.addView(listContainer);
        root.addView(scroll);

        // 3. Footer: Sleek Close Button
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        footParams.topMargin = (int) (14 * density);
        footer.setLayoutParams(footParams);

        TextView btnDone = new TextView(this);
        btnDone.setText("Close");
        btnDone.setTextSize(11f);
        btnDone.setTypeface(null, Typeface.BOLD);
        btnDone.setBackgroundResource(R.drawable.dialog_done_btn_selector);
        btnDone.setTextColor(getResources().getColorStateList(R.drawable.dialog_done_btn_text_selector));
        btnDone.setFocusable(true);
        btnDone.setClickable(true);
        btnDone.setGravity(Gravity.CENTER);
        btnDone.setPadding((int) (32 * density), (int) (7 * density), (int) (32 * density), (int) (7 * density));
        btnDone.setOnFocusChangeListener(focusAnimator);
        btnDone.setOnClickListener(v -> dialog.dismiss());

        footer.addView(btnDone);
        root.addView(footer);

        dialog.setContentView(root);
        dialog.show();
    }

    // --- RAM Purge ---

    private void purgeRam() {
        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc == null || svc.getSystemDoctor() == null) return;

        executor.execute(() -> {
            List<String> purged = svc.getSystemDoctor().purgeBackgroundApps();
            mainHandler.post(() -> {
                refreshSystemDiagnostics();
                Toast.makeText(this, "Cleaned RAM. Stopped " + purged.size() + " background processes.", Toast.LENGTH_SHORT).show();
            });
        });
    }

    // --- Speed Test Engine with Multi-Metric Readout (Requirement 4) ---

    private void applySpeedTestResult(String resultJson) {
        if (resultJson == null || tvSpeedReadout == null) return;
        try {
            JSONObject obj = new JSONObject(resultJson);
            if (obj.optBoolean("success", false)) {
                double downMbps = obj.optDouble("downMbps", obj.optDouble("mbps", 0.0));
                double upMbps = obj.optDouble("upMbps", 0.0);
                int latency = obj.optInt("latencyMs", 0);
                int jitter = obj.optInt("jitterMs", 0);

                tvSpeedReadout.setText(String.format(Locale.US, "↓ %.1f Mbps  ↑ %.1f Mbps", downMbps, upMbps));
                tvSpeedReadout.setTextColor(0xFF00E676); // Green when complete
                if (tvSpeedSubtext != null) {
                    tvSpeedSubtext.setText(String.format(Locale.US, "Latency: %d ms • Jitter: %d ms", latency, jitter));
                }
            } else {
                tvSpeedReadout.setText("Speed Test Failed");
                tvSpeedReadout.setTextColor(0xFFFF5252);
                if (tvSpeedSubtext != null) {
                    tvSpeedSubtext.setText("Offline or timeout");
                }
            }
        } catch (Exception e) {
            tvSpeedReadout.setText("Offline");
        }
    }

    private void runSpeedCheckAsync() {
        if (tvSpeedReadout != null) {
            tvSpeedReadout.setText("Testing Speed...");
            tvSpeedReadout.setTextColor(0xFF00A8FF); // Blue while running
        }
        if (tvSpeedSubtext != null) {
            tvSpeedSubtext.setText("Connecting to Cloudflare CDN...");
        }

        executor.execute(() -> {
            OmniDeckService svc = OmniDeckService.getInstance();
            if (svc == null || svc.getSystemDoctor() == null) return;

            String resultJson = svc.getSystemDoctor().runSpeedTest();
            mainHandler.post(() -> applySpeedTestResult(resultJson));
        });
    }

    // --- NoteBoard & Confined Vertical Multi-Clip History (Requirement 3) ---

    @Override
    public void onNoteChanged(NoteBoardStore.ClipItem activeClip) {
        mainHandler.post(() -> updateNotePreview(activeClip));
    }

    private void updateNotePreview(NoteBoardStore.ClipItem clip) {
        if (clip == null) return;

        if (tvClipSender != null) {
            long agoSec = (System.currentTimeMillis() - clip.timestampMs) / 1000;
            String timeStr = agoSec < 60 ? "Just now" : ((agoSec / 60) + "m ago");
            tvClipSender.setText("From [" + clip.osType.toUpperCase() + "] " + clip.sender + " • " + timeStr);
        }

        if (tvNotePreview != null) {
            if (clip.text == null || clip.text.trim().isEmpty()) {
                tvNotePreview.setText("No shared notes yet. Send text or links from phone companion.");
            } else {
                tvNotePreview.setText(clip.text);
                android.util.Log.i("OmniDeck", "Note updated: " + clip.text);
            }
        }

        // Render Confined Vertical History with Full Device Sender Info
        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc != null && svc.getNoteBoard() != null && llClipHistory != null) {
            List<NoteBoardStore.ClipItem> history = svc.getNoteBoard().getHistory();
            llClipHistory.removeAllViews();

            int itemPad = (int) (6 * getResources().getDisplayMetrics().density);
            int marginB = (int) (4 * getResources().getDisplayMetrics().density);

            for (NoteBoardStore.ClipItem item : history) {
                LinearLayout itemLayout = new LinearLayout(this);
                itemLayout.setOrientation(LinearLayout.VERTICAL);
                itemLayout.setBackgroundResource(R.drawable.pill_selector);
                itemLayout.setPadding(itemPad * 2, itemPad, itemPad * 2, itemPad);
                itemLayout.setFocusable(true);
                itemLayout.setClickable(true);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                lp.bottomMargin = marginB;
                itemLayout.setLayoutParams(lp);

                // Sender and Time header
                LinearLayout topRow = new LinearLayout(this);
                topRow.setOrientation(LinearLayout.HORIZONTAL);
                topRow.setGravity(Gravity.CENTER_VERTICAL);

                TextView tvItemSender = new TextView(this);
                tvItemSender.setText("[" + item.osType.toUpperCase() + "] " + item.sender);
                tvItemSender.setTextColor(0xFF00A8FF);
                tvItemSender.setTextSize(10f);
                tvItemSender.setTypeface(null, Typeface.BOLD);
                tvItemSender.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

                TextView tvItemTime = new TextView(this);
                long secAgo = Math.max(0, (System.currentTimeMillis() - item.timestampMs) / 1000L);
                String timeStr = secAgo < 60 ? (secAgo + "s ago") : ((secAgo / 60) + "m ago");
                tvItemTime.setText(timeStr);
                tvItemTime.setTextColor(0xFF64748B);
                tvItemTime.setTextSize(9f);

                topRow.addView(tvItemSender);
                topRow.addView(tvItemTime);

                // Snippet body
                TextView tvItemText = new TextView(this);
                String rawSnippet = item.text.replace("\n", " ").trim();
                if (rawSnippet.length() > 65) rawSnippet = rawSnippet.substring(0, 62) + "...";
                tvItemText.setText(rawSnippet);
                tvItemText.setTextColor(0xFFF1F5F9);
                tvItemText.setTextSize(11f);
                tvItemText.setMaxLines(2);
                tvItemText.setEllipsize(android.text.TextUtils.TruncateAt.END);

                itemLayout.addView(topRow);
                itemLayout.addView(tvItemText);

                itemLayout.setOnFocusChangeListener((v, hasFocus) -> {
                    tvItemText.setTextColor(hasFocus ? 0xFF000000 : 0xFFF1F5F9);
                    tvItemSender.setTextColor(hasFocus ? 0xFF003366 : 0xFF00A8FF);
                    v.animate().scaleX(hasFocus ? 1.02f : 1.0f).scaleY(hasFocus ? 1.02f : 1.0f).setDuration(100).start();
                });

                itemLayout.setOnClickListener(v -> {
                    svc.getNoteBoard().copyToClipboard(item);
                    updateNotePreview(item);
                    Toast.makeText(this, "Copied to TV Clipboard!", Toast.LENGTH_SHORT).show();
                });

                llClipHistory.addView(itemLayout);
            }
        }
    }

    private void copyNoteToClipboard() {
        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc != null && svc.getNoteBoard() != null) {
            boolean success = svc.getNoteBoard().copyToClipboard();
            if (success) {
                Toast.makeText(this, "Copied note to TV clipboard! Long-press OK to paste.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Note is empty.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void clearClipboardHistory() {
        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc != null && svc.getNoteBoard() != null) {
            svc.getNoteBoard().clearHistory();
            Toast.makeText(this, "Cleared clipboard history", Toast.LENGTH_SHORT).show();
            if (tvNotePreview != null) {
                tvNotePreview.setText("Clipboard cleared. Send new text or links from companion.");
            }
            if (llClipHistory != null) {
                llClipHistory.removeAllViews();
            }
        }
    }

    private void exitAppCleanly() {
        Toast.makeText(this, "Exiting OmniDeck...", Toast.LENGTH_SHORT).show();
        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc != null) {
            svc.stopForeground(true);
            svc.stopSelf();
        }
        stopService(new Intent(this, OmniDeckService.class));
        finishAffinity();
        mainHandler.postDelayed(() -> {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }, 250);
    }

    // --- System Controls: Animation Scale & File Vault (Requirement 5 & 6) ---

    private void setAnimationScale(float scale) {
        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc != null) {
            svc.setAnimationScale(scale);
            updateAnimationScaleButtons(scale);
            Toast.makeText(this, "Animation scale set to " + scale + "x", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateAnimationScaleButtons(float currentScale) {
        if (btnAnimOff == null || btnAnimSnappy == null || btnAnimNormal == null) return;
        boolean isOff = Math.abs(currentScale - 0.0f) < 0.1f;
        boolean isSnappy = Math.abs(currentScale - 0.5f) < 0.1f;
        boolean isNormal = Math.abs(currentScale - 1.0f) < 0.1f;

        btnAnimOff.setTextColor(isOff ? 0xFF00E5FF : 0xFF94A3B8);
        btnAnimSnappy.setTextColor(isSnappy ? 0xFF00E5FF : 0xFF94A3B8);
        btnAnimNormal.setTextColor(isNormal ? 0xFF00E5FF : 0xFF94A3B8);
    }

    private void cycleVaultFolder() {
        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc != null) {
            String next = svc.cycleVaultFolder();
            if (btnVaultFolder != null) {
                btnVaultFolder.setText("📁 /sdcard/" + next);
            }
            Toast.makeText(this, "Vault folder: /sdcard/" + next, Toast.LENGTH_SHORT).show();
        }
    }
}
