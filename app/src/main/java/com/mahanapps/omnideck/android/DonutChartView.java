package com.mahanapps.omnideck.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * OLED-optimized minimalist donut chart showing storage breakdown.
 */
public final class DonutChartView extends View {

    private long appsBytes = 0;
    private long mediaBytes = 0;
    private long downloadsBytes = 0;
    private long systemBytes = 0;
    private long freeBytes = 0;
    private long totalBytes = 1;

    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private static final int COLOR_FREE = 0xFF00E676;      // Emerald
    private static final int COLOR_APPS = 0xFF00A8FF;      // Blue
    private static final int COLOR_MEDIA = 0xFFFFB300;     // Amber
    private static final int COLOR_DOWNLOADS = 0xFF00E5FF; // Cyan
    private static final int COLOR_SYSTEM = 0xFF7E57C2;    // Purple
    private static final int COLOR_TRACK = 0xFF141C2B;     // Background ring track

    public DonutChartView(Context context) {
        super(context);
        init();
    }

    public DonutChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.BUTT);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(COLOR_TRACK);

        centerTextPaint.setColor(0xFFF8FAFC);
        centerTextPaint.setTextSize(22f);
        centerTextPaint.setFakeBoldText(true);
        centerTextPaint.setTextAlign(Paint.Align.CENTER);

        subTextPaint.setColor(0xFF8B95A5);
        subTextPaint.setTextSize(12f);
        subTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setStorageData(long total, long free, long apps, long media, long downloads, long system) {
        this.totalBytes = Math.max(1L, total);
        this.freeBytes = free;
        this.appsBytes = apps;
        this.mediaBytes = media;
        this.downloadsBytes = downloads;
        this.systemBytes = system;
        invalidate();
    }

    public void setStorageData(long total, long free, long apps, long media, long system) {
        setStorageData(total, free, apps, media, 0, system);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float stroke = Math.min(w, h) * 0.11f;
        arcPaint.setStrokeWidth(stroke);
        trackPaint.setStrokeWidth(stroke);

        float pad = stroke / 2.0f + 4f;
        oval.set(pad, pad, w - pad, h - pad);

        // Draw track ring
        canvas.drawOval(oval, trackPaint);

        float startAngle = -90f;
        float totalF = (float) totalBytes;

        // Draw Apps segment
        float sweepApps = (appsBytes / totalF) * 360f;
        arcPaint.setColor(COLOR_APPS);
        canvas.drawArc(oval, startAngle, sweepApps, false, arcPaint);
        startAngle += sweepApps;

        // Draw Media segment
        float sweepMedia = (mediaBytes / totalF) * 360f;
        arcPaint.setColor(COLOR_MEDIA);
        canvas.drawArc(oval, startAngle, sweepMedia, false, arcPaint);
        startAngle += sweepMedia;

        // Draw Downloads segment
        float sweepDl = (downloadsBytes / totalF) * 360f;
        if (sweepDl > 0) {
            arcPaint.setColor(COLOR_DOWNLOADS);
            canvas.drawArc(oval, startAngle, sweepDl, false, arcPaint);
            startAngle += sweepDl;
        }

        // Draw System segment
        float sweepSys = (systemBytes / totalF) * 360f;
        arcPaint.setColor(COLOR_SYSTEM);
        canvas.drawArc(oval, startAngle, sweepSys, false, arcPaint);
        startAngle += sweepSys;

        // Draw Free segment
        float sweepFree = Math.max(0, 360f - (sweepApps + sweepMedia + sweepDl + sweepSys));
        arcPaint.setColor(COLOR_FREE);
        canvas.drawArc(oval, startAngle, sweepFree, false, arcPaint);

        // Center readout
        float cx = w / 2.0f;
        float cy = h / 2.0f;
        String freeGb = String.format(java.util.Locale.US, "%.1f GB", freeBytes / (1024.0 * 1024.0 * 1024.0));
        canvas.drawText(freeGb, cx, cy + 2, centerTextPaint);
        canvas.drawText("Free", cx, cy + 18, subTextPaint);
    }
}
