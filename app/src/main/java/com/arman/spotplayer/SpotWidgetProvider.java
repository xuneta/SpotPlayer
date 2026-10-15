package com.arman.spotplayer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.widget.RemoteViews;

/** Home-screen widget: art, title/artist, prev/play/next into PlaybackService. */
public class SpotWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        Song s = Player.get().current();
        Bitmap art = s != null ? MusicLibrary.cachedArt(s.albumId) : null;
        SpotWidget.update(context, s, Player.get().isPlaying(), art);
    }
}

final class SpotWidget {

    private SpotWidget() {}

    static void update(Context ctx, Song s, boolean playing, Bitmap art) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            ComponentName cn = new ComponentName(ctx, SpotWidgetProvider.class);
            if (mgr.getAppWidgetIds(cn).length == 0) return;
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_player);
            v.setTextViewText(R.id.widgetTitle, s != null ? s.title : "SpotPlayer");
            v.setTextViewText(R.id.widgetArtist, s != null ? s.artist : "Pick a song");
            v.setImageViewResource(R.id.widgetPlayIcon,
                    playing ? R.drawable.ic_pause : R.drawable.ic_play);
            if (art != null) {
                v.setImageViewBitmap(R.id.widgetArt, rounded(art, ctx));
            } else {
                v.setImageViewResource(R.id.widgetArt, R.drawable.ic_note);
            }
            v.setOnClickPendingIntent(R.id.widgetPlayIcon,
                    action(ctx, PlaybackService.ACTION_TOGGLE, 11));
            v.setOnClickPendingIntent(R.id.widgetNext,
                    action(ctx, PlaybackService.ACTION_NEXT, 12));
            v.setOnClickPendingIntent(R.id.widgetPrev,
                    action(ctx, PlaybackService.ACTION_PREV, 13));
            mgr.updateAppWidget(cn, v);
        } catch (Exception ignored) {}
    }

    private static PendingIntent action(Context ctx, String action, int req) {
        Intent i = new Intent(ctx, PlaybackService.class).setAction(action);
        return PendingIntent.getService(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Center-crop to a square with rounded corners, sized for the widget. */
    private static Bitmap rounded(Bitmap src, Context ctx) {
        int size = Math.round(56 * ctx.getResources().getDisplayMetrics().density);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float radius = 8 * ctx.getResources().getDisplayMetrics().density;
        canvas.drawRoundRect(new RectF(0, 0, size, size), radius, radius, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        int sw = src.getWidth();
        int sh = src.getHeight();
        int side = Math.min(sw, sh);
        Rect srcRect = new Rect((sw - side) / 2, (sh - side) / 2,
                (sw - side) / 2 + side, (sh - side) / 2 + side);
        canvas.drawBitmap(src, srcRect, new RectF(0, 0, size, size), paint);
        return out;
    }
}
