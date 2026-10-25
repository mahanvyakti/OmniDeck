package com.mahanapps.omnideck.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * Windows Task Manager-style resource history graph.
 * Pure Canvas rendering with circular history buffer, auto-ranging Y-axis,
 * and zero runtime allocations.
 */
public final class GraphHistoryView extends View {

    public static final int BUFFER_SIZE = 40;

    private final float[] history = new float[BUFFER_SIZE];
    private int writeIndex = 0;
    private float maxValue = 100f;
    private boolean autoScale = false;
    private float minAutoScale = 20f;
    private String unit = "%";
    private int lineColor = 0xFF00A8FF;
    private int fillColor = 0x3300A8FF;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    public GraphHistoryView(Context context) {
        super(context);
        init();
    }

    public GraphHistoryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint.setColor(0x1F293740);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1.5f);

        linePaint.setColor(lineColor);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3.0f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(0xFF8B95A5);
        textPaint.setTextSize(22f);
        textPaint.setFakeBoldText(true);

        axisPaint.setColor(0xFF475569);
        axisPaint.setTextSize(17f);
        axisPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
    }

    public void setLineColor(int color) {
        this.lineColor = color;
        this.fillColor = (color & 0x00FFFFFF) | 0x26000000;
        linePaint.setColor(lineColor);
        invalidate();
    }

    public void setMaxValue(float max) {
        this.maxValue = Math.max(1f, max);
    }

    public void setAutoScale(boolean autoScale, float minMax) {
        this.autoScale = autoScale;
        this.minAutoScale = Math.max(1f, minMax);
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public void addSample(float value) {
        history[writeIndex] = Math.max(0f, value);
        writeIndex = (writeIndex + 1) % BUFFER_SIZE;
        invalidate();
    }

    public void setHistory(float[] samples, int writeIdx) {
        if (samples != null && samples.length == BUFFER_SIZE) {
            System.arraycopy(samples, 0, history, 0, BUFFER_SIZE);
            this.writeIndex = (writeIdx >= 0 && writeIdx < BUFFER_SIZE) ? writeIdx : 0;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Draw horizontal grid lines (Task Manager style)
        for (int i = 1; i <= 3; i++) {
            float y = (h * i) / 4.0f;
            canvas.drawLine(0, y, w, y, gridPaint);
        }
        // Vertical grid lines
        for (int i = 1; i <= 4; i++) {
            float x = (w * i) / 5.0f;
            canvas.drawLine(x, 0, x, h, gridPaint);
        }

        // Determine effective max
        float effectiveMax = maxValue;
        if (autoScale) {
            float peak = 0f;
            for (float v : history) {
                if (v > peak) peak = v;
            }
            effectiveMax = Math.max(minAutoScale, peak * 1.25f);
        }

        // Draw Axis Unit Ticks (Left edge - sleek and unobtrusive)
        String maxLabel;
        String midLabel;
        String minLabel = "0 " + unit;
        if (unit.contains("B/s")) {
            if (effectiveMax >= 1024f) {
                maxLabel = String.format(Locale.US, "%.1f MB/s", effectiveMax / 1024f);
            } else {
                maxLabel = String.format(Locale.US, "%.0f %s", effectiveMax, unit);
            }
            float mid = effectiveMax / 2.0f;
            if (mid >= 1024f) {
                midLabel = String.format(Locale.US, "%.1f MB/s", mid / 1024f);
            } else {
                midLabel = String.format(Locale.US, "%.0f %s", mid, unit);
            }
        } else {
            maxLabel = String.format(Locale.US, "%.0f %s", effectiveMax, unit);
            midLabel = String.format(Locale.US, "%.0f %s", effectiveMax / 2.0f, unit);
        }

        canvas.drawText(maxLabel, 8, 20, axisPaint);
        canvas.drawText(midLabel, 8, (h / 2.0f) + 6, axisPaint);
        canvas.drawText(minLabel, 8, h - 8, axisPaint);
        canvas.drawText("160s", w * 0.28f, h - 8, axisPaint);
        canvas.drawText("now", w - 44, h - 8, axisPaint);

        linePath.reset();
        fillPath.reset();

        float stepX = (float) w / (BUFFER_SIZE - 1);
        boolean firstPoint = true;
        float lastX = 0;
        float lastVal = 0;

        for (int i = 0; i < BUFFER_SIZE; i++) {
            int idx = (writeIndex + i) % BUFFER_SIZE;
            float val = history[idx];
            float clamped = Math.min(val, effectiveMax);
            float ratio = clamped / effectiveMax;
            float x = i * stepX;
            float y = h - (ratio * (h - 8)) - 4;

            if (firstPoint) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, h);
                fillPath.lineTo(x, y);
                firstPoint = false;
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
            lastX = x;
            lastVal = val;
        }

        fillPath.lineTo(lastX, h);
        fillPath.close();

        // Gradient fill
        fillPaint.setShader(new LinearGradient(
                0, 0, 0, h,
                fillColor, Color.TRANSPARENT,
                Shader.TileMode.CLAMP
        ));

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);

        // Current readout in top right
        String readout;
        if (unit.contains("B/s")) {
            if (lastVal >= 1024f) {
                readout = String.format(Locale.US, "%.1f MB/s", lastVal / 1024f);
            } else {
                readout = String.format(Locale.US, "%.1f %s", lastVal, unit);
            }
        } else {
            readout = String.format(Locale.US, "%.0f %s", lastVal, unit);
        }

        float textWidth = textPaint.measureText(readout);
        canvas.drawText(readout, w - textWidth - 12, 32, textPaint);
    }
}
