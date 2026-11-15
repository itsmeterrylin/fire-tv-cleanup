package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Spaces the dock row. The dock's N icons share one grid row (N equal cells), so each icon is shifted toward the
 * centre to keep the same spacing a full dock has, keeping the group centred.
 *
 * Also draws the frosted-glass shelf behind the dock icons: the wallpaper blurred and darkened (setFrost), seen
 * through a rounded, faintly white panel with a thin light edge. Only the shelf is frosted; the rest of the photo
 * stays sharp, as with the macOS dock. Drawn under the items, so it scrolls with the dock for free.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    static final float SHELF_PAD_X_DP = 22f;
    static final float SHELF_PAD_Y_DP = 16f;
    private static final float SHELF_RADIUS_DP = 34f;

    private final AppAdapter adapter;
    private final int columns;
    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect cards = new Rect();
    private final RectF shelf = new RectF();
    private Bitmap frost;
    private final Paint frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix frostMatrix = new Matrix();
    private final int[] where = new int[2];

    DockDecoration(AppAdapter adapter, int columns, float density) {
        this.adapter = adapter;
        this.columns = columns;
        this.density = density;
        fill.setColor(0x1FFFFFFF);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, density));
        edge.setColor(0x47FFFFFF);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        int n = adapter.dockCount();
        if (pos < 0 || pos >= n) return;
        outRect.top = (int) (6 * density);
        outRect.bottom = (int) (12 * density);
        float inner = parent.getMeasuredWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        float actual = (pos + 0.5f) * inner / n;
        float wanted = inner / 2f + (pos - (n - 1) / 2f) * inner / columns;
        int shift = Math.round(2 * (wanted - actual));   // the item's content is centred: offsets move it by half
        if (shift > 0) outRect.left = shift;
        else outRect.right = -shift;
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (!dockCards(parent, cards)) return;
        shelf.set(cards.left - SHELF_PAD_X_DP * density, cards.top - SHELF_PAD_Y_DP * density,
                cards.right + SHELF_PAD_X_DP * density, cards.bottom + SHELF_PAD_Y_DP * density);
        float r = SHELF_RADIUS_DP * density;
        if (frost != null && frostPaint.getAlpha() > 0) {
            // The blurred photo, laid exactly where the sharp one is (centre-cropped to the screen), seen
            // through the shelf's rounded shape.
            View root = parent.getRootView();
            parent.getLocationInWindow(where);
            float w = root.getWidth(), h = root.getHeight();
            float scale = Math.max(w / frost.getWidth(), h / frost.getHeight());
            frostMatrix.setScale(scale, scale);
            frostMatrix.postTranslate((w - frost.getWidth() * scale) / 2f - where[0],
                    (h - frost.getHeight() * scale) / 2f - where[1]);
            frostPaint.getShader().setLocalMatrix(frostMatrix);
            c.drawRoundRect(shelf, r, r, frostPaint);
        }
        c.drawRoundRect(shelf, r, r, fill);
        float inset = edge.getStrokeWidth() / 2f;
        shelf.inset(inset, inset);
        c.drawRoundRect(shelf, r, r, edge);
    }

    /** The blurred, darkened wallpaper shown through the shelf; null for none (the plain glass). */
    void setFrost(Bitmap blurred) {
        frost = blurred;
        frostPaint.setShader(blurred == null ? null : new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    }

    /** Fades the frost with the wallpaper swap (0-255). */
    void setFrostAlpha(int alpha) {
        frostPaint.setAlpha(alpha);
    }

    /** The union of the dock icons' cards in the grid's coordinates (layout positions, ignoring magnification). */
    boolean dockCards(RecyclerView parent, Rect out) {
        out.setEmpty();
        int n = adapter.dockCount();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View item = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(item);
            if (pos < 0 || pos >= n) continue;
            View card = item.findViewById(R.id.card);
            if (card == null) continue;
            int left = item.getLeft() + card.getLeft();
            int top = Math.round(item.getTop() + item.getTranslationY()) + card.getTop();
            out.union(left, top, left + card.getWidth(), top + card.getHeight());
        }
        return !out.isEmpty();
    }
}
