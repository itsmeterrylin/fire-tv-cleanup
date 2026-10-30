package com.gathin.tvhome;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.List;

/**
 * Screensaver: full-screen photos with a slow zoom, following whatever wallpaper source the home screen
 * is set to (gear menu -> Wallpaper) and the same rotation interval. Shows a large clock with AM/PM and
 * today's weather, with dark or light text depending on how bright the photo is.
 *
 * Chosen with:
 *   settings put secure screensaver_components com.gathin.tvhome/com.gathin.tvhome.PhotoDream
 * and Amazon's back with:
 *   settings put secure screensaver_components com.amazon.ftv.screensaver/.app.services.ScreensaverService
 */
public class PhotoDream extends DreamService {

    static final int[] PHOTOS = {   // the "bundled photos" wallpaper pack
            R.drawable.saver_01, R.drawable.saver_02, R.drawable.saver_03, R.drawable.saver_04,
            R.drawable.saver_05, R.drawable.saver_06, R.drawable.saver_07, R.drawable.saver_08,
            R.drawable.saver_09, R.drawable.saver_10, R.drawable.saver_11, R.drawable.saver_12};

    private static final long FADE_MS = 2_000;
    private static final float ZOOM = 1.08f;   // reached over one slide, then the next photo takes over

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ImageView front, back;
    private View info;
    private TextClock clock;
    private TextView ampm, weather;
    private int index;
    private boolean darkText;
    private boolean running;
    private boolean hasImage;

    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            showNext();
            handler.postDelayed(this, slideMs());
        }
    };

    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            loadWeather();
            handler.postDelayed(this, 30 * 60_000L);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = photoView();
        front = photoView();
        root.addView(back);
        root.addView(front);
        root.addView(infoView());
        setContentView(root);
        front.setAlpha(0f);
        back.setAlpha(0f);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        handler.post(advance);
        handler.post(weatherTick);
    }

    @Override
    public void onDreamingStopped() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        super.onDreamingStopped();
    }

    /** Same cadence the home screen uses for its wallpaper. */
    private long slideMs() {
        return Math.max(1, WallpaperEngine.intervalMinutes(this)) * 60_000L;
    }

    // ---- photos -------------------------------------------------------------------------------

    private void showNext() {
        String source = WallpaperEngine.source(this);
        if (WallpaperEngine.SOURCE_TIME.equals(source)) {
            int[] photos = WallpaperEngine.timeOfDay();
            showRes(photos[index++ % photos.length]);
        } else if (WallpaperEngine.SOURCE_RANDOM.equals(source)) {
            if (!hasImage) showRes(bundledOrder()[0]);      // never leave the screen black while it downloads
            final String url = WallpaperEngine.randomUrl(new java.util.Random());
            new Thread(() -> {
                try {
                    Bitmap b = WallpaperEngine.download(url);
                    if (b != null) handler.post(() -> showBitmap(b));
                } catch (Exception ignored) {
                }
            }, "dream-photo").start();
        } else if (WallpaperEngine.SOURCE_PHOTOS.equals(source)) {
            List<java.io.File> files = WallpaperEngine.photos(this);
            if (files.isEmpty()) {
                if (!hasImage) showRes(bundledOrder()[0]);
                return;
            }
            final java.io.File f = files.get(index++ % files.size());
            new Thread(() -> {
                Bitmap b = WallpaperEngine.decode(f);
                if (b != null) handler.post(() -> showBitmap(b));
            }, "dream-photo").start();
        } else {
            int[] order = bundledOrder();
            showRes(order[index++ % order.length]);
        }
    }

    private int[] bundledOrder;

    private int[] bundledOrder() {
        if (bundledOrder == null) {
            bundledOrder = PHOTOS.clone();
            java.util.Random r = new java.util.Random();
            for (int i = bundledOrder.length - 1; i > 0; i--) {
                int j = r.nextInt(i + 1);
                int t = bundledOrder[i];
                bundledOrder[i] = bundledOrder[j];
                bundledOrder[j] = t;
            }
        }
        return bundledOrder;
    }


    /** Fades the incoming photo in and restarts its slow zoom, without the two fighting. */
    private void animateIn(ImageView incoming) {
        incoming.animate().cancel();
        incoming.setScaleX(1f);
        incoming.setScaleY(1f);
        incoming.animate().alpha(1f).setDuration(FADE_MS).start();
        android.animation.ObjectAnimator.ofFloat(incoming, "scaleX", 1f, ZOOM)
                .setDuration(slideMs() + FADE_MS).start();
        android.animation.ObjectAnimator.ofFloat(incoming, "scaleY", 1f, ZOOM)
                .setDuration(slideMs() + FADE_MS).start();
    }

    private void showRes(int res) {
        if (!running) return;
        hasImage = true;
        applyContrast(WallpaperEngine.luminance(this, res));
        ImageView incoming = back, outgoing = front;
        incoming.setImageResource(res);
        incoming.setAlpha(0f);
        incoming.bringToFront();
        info.bringToFront();
        animateIn(incoming);
        outgoing.animate().alpha(0f).setStartDelay(FADE_MS).setDuration(1).start();
        front = incoming;
        back = outgoing;
    }

    private void showBitmap(Bitmap bitmap) {
        if (!running) return;
        hasImage = true;
        applyContrast(WallpaperEngine.luminance(bitmap));
        ImageView incoming = back, outgoing = front;
        incoming.setImageBitmap(bitmap);
        incoming.setAlpha(0f);
        incoming.bringToFront();
        info.bringToFront();
        animateIn(incoming);
        outgoing.animate().alpha(0f).setStartDelay(FADE_MS).setDuration(1).start();
        front = incoming;
        back = outgoing;
    }

    // ---- text and weather ---------------------------------------------------------------------

    private View infoView() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.END);

        weather = new TextView(this);
        weather.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        weather.setCompoundDrawablePadding((int) (10 * getResources().getDisplayMetrics().density));
        weather.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.open_sans_medium));
        column.addView(weather);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.BOTTOM);

        clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46);
        clock.setIncludeFontPadding(false);
        clock.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.open_sans_light));
        timeRow.addView(clock);

        // AM/PM as its own clock so it follows the 12/24-hour setting.
        TextClock ampmClock = new TextClock(this);
        ampmClock.setFormat12Hour("a");
        ampmClock.setFormat24Hour("");
        ampmClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        ampmClock.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.open_sans_medium));
        ampmClock.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams amp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        amp.setMargins((int) (8 * getResources().getDisplayMetrics().density), 0, 0, 0);
        amp.gravity = Gravity.BOTTOM;
        timeRow.addView(ampmClock, amp);
        ampm = ampmClock;

        column.addView(timeRow);

        float dp = getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, (int) (48 * dp), (int) (36 * dp));
        column.setLayoutParams(lp);
        info = column;
        return column;
    }

    /** Dark text over a bright photo, white over a dark one; the shadow flips with it. */
    private void applyContrast(float luminance) {
        darkText = luminance > 0.55f;
        int primary = darkText ? 0xFF0E0E0F : 0xE6FFFFFF;
        int secondary = darkText ? 0xCC000000 : 0xB3FFFFFF;
        int shadow = darkText ? 0x66FFFFFF : 0x99000000;
        clock.setTextColor(primary);
        clock.setShadowLayer(12f, 0f, 2f, shadow);
        ampm.setTextColor(secondary);
        ampm.setShadowLayer(8f, 0f, 1f, shadow);
        weather.setTextColor(secondary);
        weather.setShadowLayer(8f, 0f, 1f, shadow);
        weather.setCompoundDrawableTintList(ColorStateList.valueOf(secondary));
    }

    private void loadWeather() {
        WeatherLoader.load(this, f -> {
            if (weather == null) return;
            weather.setCompoundDrawablesRelativeWithIntrinsicBounds(WeatherLoader.iconRes(f.code), 0, 0, 0);
            weather.setText(String.format(java.util.Locale.getDefault(), "%s   H %s   L %s",
                    f.degrees(f.temp), f.degrees(f.high), f.degrees(f.low)));
            weather.setCompoundDrawableTintList(ColorStateList.valueOf(darkText ? 0xCC000000 : 0xB3FFFFFF));
        });
    }

    private ImageView photoView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return v;
    }
}
