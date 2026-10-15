package com.arman.spotplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * SoundCloud-model waveform seekbar: mirrored bars, played portion green,
 * rest dark gray. Tap or drag seeks proportionally. While peaks are missing
 * the caller keeps this view GONE and shows the plain SeekBar instead.
 */
public class WaveformView extends View {

    public interface SeekListener {
        void onSeekStart();
        void onSeek(float fraction); // 0..1
        void onSeekEnd();
    }

    private float[] peaks;
    private float progress; // 0..1
    private SeekListener listener;
    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint restPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean dragging = false;

    public WaveformView(Context c) {
        super(c);
        init(c);
    }

    public WaveformView(Context c, AttributeSet a) {
        super(c, a);
        init(c);
    }

    private void init(Context c) {
        playedPaint.setColor(0xFF1ABC54);
        restPaint.setColor(0xFF535353);
        setContentDescription("Waveform seekbar");
        setFocusable(true);
    }

    public void setPeaks(float[] p) {
        peaks = p;
        invalidate();
    }

    public void setProgress(float f) {
        float nf = Math.max(0f, Math.min(1f, f));
        if (Math.abs(nf - progress) > 0.0005f) {
            progress = nf;
            if (!dragging) invalidate();
        }
    }

    public void setSeekListener(SeekListener l) {
        listener = l;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (peaks == null || peaks.length == 0) return;
        int w = getWidth();
        int h = getHeight();
        int n = peaks.length;
        float gap = 2f * getResources().getDisplayMetrics().density;
        float barW = (w - gap * (n - 1)) / n;
        if (barW < 1f) barW = 1f;
        float radius = barW / 2f;
        int playedBars = Math.round(progress * n);
        for (int i = 0; i < n; i++) {
            float ph = Math.max(2f, peaks[i] * h);
            float left = i * (barW + gap);
            float top = (h - ph) / 2f;
            canvas.drawRoundRect(left, top, left + barW, top + ph,
                    radius, radius, i < playedBars ? playedPaint : restPaint);
        }
    }

    private float fractionFor(float x) {
        int w = getWidth();
        if (w <= 0) return 0f;
        return Math.max(0f, Math.min(1f, x / w));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (listener != null) listener.onSeekStart();
                // fall through
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    float f = fractionFor(e.getX());
                    progress = f;
                    invalidate();
                    if (listener != null) listener.onSeek(f);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    if (listener != null) listener.onSeekEnd();
                }
                return true;
        }
        return super.onTouchEvent(e);
    }
}
