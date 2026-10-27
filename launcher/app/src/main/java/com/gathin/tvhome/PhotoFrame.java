package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Photo frame on the home screen: your own photos in random order, crossfading every SLIDE_MS.
 *
 * Photos are JPEGs in the app's own folder, which needs no storage permission:
 *   /sdcard/Android/data/com.gathin.tvhome/files/photos
 * tools/push_photos.py fills it from a downloaded Google Photos album. Hidden while the folder is empty.
 * Decodes one photo at a time off the main thread, at frame size, to stay light on 1 GB of RAM.
 */
public class PhotoFrame extends FrameLayout {

    private static final long SLIDE_MS = 30_000;
    private static final long FADE_MS = 1_200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final List<File> photos = new ArrayList<>();
    private ImageView front, back;
    private int next;
    private boolean running;

    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            showNext();
            handler.postDelayed(this, SLIDE_MS);
        }
    };

    public PhotoFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipToOutline(true);   // round the photos to the background's corners (the XML attribute is API 31+)
        back = photoView();
        front = photoView();
        addView(back);
        addView(front);
    }

    static File folder(Context ctx) {
        return new File(ctx.getExternalFilesDir(null), "photos");
    }

    /** Rescans the folder, so newly pushed photos show up, and starts the slideshow. */
    void start() {
        stop();
        photos.clear();
        File[] files = folder(getContext()).listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jpg"));
        if (files != null) Collections.addAll(photos, files);
        Collections.shuffle(photos);
        next = 0;
        setVisibility(photos.isEmpty() ? GONE : VISIBLE);
        if (photos.isEmpty()) return;
        running = true;
        handler.post(advance);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(advance);
    }

    private void showNext() {
        File file = photos.get(next);
        next = (next + 1) % photos.size();
        int w = getLayoutParams().width, h = getLayoutParams().height;
        decoder.execute(() -> {
            Bitmap bm = decode(file, w, h);
            handler.post(() -> {
                if (running && bm != null) crossfade(bm);
            });
        });
    }

    private void crossfade(Bitmap bm) {
        ImageView incoming = back, outgoing = front;
        incoming.setImageBitmap(bm);
        incoming.setAlpha(0f);
        incoming.bringToFront();
        incoming.animate().alpha(1f).setDuration(FADE_MS)
                .withEndAction(() -> outgoing.setImageDrawable(null));   // let the old bitmap go
        front = incoming;
        back = outgoing;
    }

    /** Smallest power-of-two downsample that still covers w x h, in RGB_565 (half the memory of ARGB). */
    private static Bitmap decode(File file, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getPath(), o);
    }

    private ImageView photoView() {
        ImageView v = new ImageView(getContext());
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        return v;
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        decoder.shutdown();
        super.onDetachedFromWindow();
    }
}
