package com.mahanapps.omnideck.android;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * OmniDeck Accessibility Service for Android TV.
 * Provides:
 * 1. System-wide full-screen screenshot capture across ALL apps using Android 11+ takeScreenshot().
 * 2. Focused input field detection and remote keystroke / text injection across apps.
 */
public final class OmniDeckAccessibilityService extends AccessibilityService implements OmniDeckService.ScreenshotProvider {

    private static volatile OmniDeckAccessibilityService instance;

    public static OmniDeckAccessibilityService getInstance() {
        return instance;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile AccessibilityNodeInfo lastFocusedInput = null;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc != null) {
            svc.setScreenshotProvider(this);
        }
    }

    @Override
    public void onDestroy() {
        OmniDeckService svc = OmniDeckService.getInstance();
        if (svc != null && svc.getScreenshotProvider() == this) {
            svc.setScreenshotProvider(null);
        }
        instance = null;
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();

        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED || type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null && source.isEditable()) {
                if (lastFocusedInput != null) {
                    try { lastFocusedInput.recycle(); } catch (Exception ignored) {}
                }
                lastFocusedInput = source;
            }
        }
    }

    @Override
    public void onInterrupt() {
        // Accessibility service interrupted
    }

    /**
     * Injects text directly into the currently focused text field on the TV.
     */
    public boolean typeTextIntoFocusedView(String text) {
        if (text == null) return false;
        AccessibilityNodeInfo node = lastFocusedInput;
        if (node == null) {
            // Attempt to find focused node in active window
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            }
        }

        if (node != null && node.isEditable()) {
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        }
        return false;
    }

    /**
     * Captures a system-wide screenshot of whatever is currently on the TV screen (any app).
     */
    @Override
    public byte[] captureScreenshotBytes(File saveToFile) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null; // Requires Android 11+ (API 30)
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final byte[][] resultHolder = new byte[1][];

        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, executor, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(@NonNull ScreenshotResult screenshot) {
                    HardwareBuffer hwBuffer = screenshot.getHardwareBuffer();
                    ColorSpace colorSpace = screenshot.getColorSpace();
                    try {
                        Bitmap hwBmp = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace);
                        if (hwBmp != null) {
                            Bitmap softwareBmp = null;
                            try {
                                softwareBmp = hwBmp.copy(Bitmap.Config.ARGB_8888, false);
                            } catch (Throwable t) {
                                android.util.Log.w("OmniDeckA11y", "copy failed, using Canvas: " + t);
                            }
                            if (softwareBmp == null) {
                                try {
                                    softwareBmp = Bitmap.createBitmap(hwBmp.getWidth(), hwBmp.getHeight(), Bitmap.Config.ARGB_8888);
                                    android.graphics.Canvas canvas = new android.graphics.Canvas(softwareBmp);
                                    canvas.drawBitmap(hwBmp, 0, 0, null);
                                } catch (Throwable t) {
                                    android.util.Log.e("OmniDeckA11y", "Canvas draw failed: " + t);
                                }
                            }
                            hwBmp.recycle();

                            if (softwareBmp != null) {
                                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                                    softwareBmp.compress(Bitmap.CompressFormat.PNG, 90, baos);
                                    byte[] pngBytes = baos.toByteArray();
                                    softwareBmp.recycle();

                                    resultHolder[0] = pngBytes;

                                    if (saveToFile != null) {
                                        try {
                                            if (saveToFile.getParentFile() != null) {
                                                saveToFile.getParentFile().mkdirs();
                                            }
                                            try (FileOutputStream fos = new FileOutputStream(saveToFile)) {
                                                fos.write(pngBytes);
                                                fos.flush();
                                            }
                                            MediaScannerConnection.scanFile(
                                                    getApplicationContext(),
                                                    new String[]{saveToFile.getAbsolutePath()},
                                                    new String[]{"image/png"},
                                                    null
                                            );
                                        } catch (Throwable t) {
                                            android.util.Log.w("OmniDeckA11y", "File write failed: " + t);
                                        }
                                    }
                                } catch (Throwable t) {
                                    android.util.Log.e("OmniDeckA11y", "PNG compression failed: " + t);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        android.util.Log.e("OmniDeckA11y", "Error processing screenshot: " + t);
                    } finally {
                        hwBuffer.close();
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    android.util.Log.w("OmniDeckA11y", "takeScreenshot onFailure code=" + errorCode);
                    latch.countDown();
                }
            });

            boolean finished = latch.await(7000, TimeUnit.MILLISECONDS);
            if (!finished) {
                android.util.Log.w("OmniDeckA11y", "takeScreenshot timed out after 7000ms");
            }
            return resultHolder[0];
        } catch (Exception e) {
            android.util.Log.e("OmniDeckA11y", "takeScreenshot exception: " + e);
            return null;
        }
    }
}
