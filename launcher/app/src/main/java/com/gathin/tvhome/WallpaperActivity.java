package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Wallpaper source and rotation speed. Reached from the gear menu.
 *
 * Rows 0-3 pick the source. Row 4 is the interval: left/right on the remote change it by a minute.
 */
public class WallpaperActivity extends Activity {

    private static final int ROW_INTERVAL = 4;
    private static final int ROW_HINT = 5;

    private String[] rows;
    private ArrayAdapter<String> adapter;
    private int minMinutes = 1;
    private int maxMinutes = 240;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(96, 40, 96, 32);
        root.setBackgroundColor(0xF2000000);

        TextView title = new TextView(this);
        title.setText(R.string.wallpaper);
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setGravity(Gravity.START);
        title.setPadding(0, 0, 0, 16);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(R.drawable.list_selector);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        adapter = new ArrayAdapter<String>(this, R.layout.settings_row, new java.util.ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                row.setText(rows[position]);
                row.setTextColor(Color.parseColor("#E6FFFFFF"));
                row.setTextSize(position == ROW_INTERVAL ? 21 : 19);
                row.setPadding(24, 18, 24, 18);
                return row;
            }
        };
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> onRow(position));
        list.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (list.getSelectedItemPosition() != ROW_INTERVAL) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { adjust(-1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { adjust(1); return true; }
            return false;
        });
        rebuild();
    }

    private void adjust(int delta) {
        int minutes = WallpaperEngine.intervalMinutes(this) + delta;
        minutes = Math.max(minMinutes, Math.min(maxMinutes, minutes));
        WallpaperEngine.setIntervalMinutes(this, minutes);
        rebuild();
    }

    private void rebuild() {
        String source = WallpaperEngine.source(this);
        int photos = WallpaperEngine.photos(this).size();
        rows = new String[]{
                mark(source, WallpaperEngine.SOURCE_TIME) + "Time of day",
                mark(source, WallpaperEngine.SOURCE_BUNDLED) + "Bundled photos (12)",
                mark(source, WallpaperEngine.SOURCE_RANDOM) + "Random photos (picsum.photos)",
                mark(source, WallpaperEngine.SOURCE_PHOTOS) + "My photos (" + photos + " in folder)",
                "  Change every " + WallpaperEngine.intervalMinutes(this) + " min   ‹  ›",
                "  Left / right changes it a minute at a time. The screensaver follows.",
        };
        adapter.clear();
        adapter.addAll(rows);
        adapter.notifyDataSetChanged();
    }

    private static String mark(String current, String value) {
        return current.equals(value) ? "✓  " : "    ";
    }

    private void onRow(int position) {
        switch (position) {
            case 0: WallpaperEngine.setSource(this, WallpaperEngine.SOURCE_TIME); break;
            case 1: WallpaperEngine.setSource(this, WallpaperEngine.SOURCE_BUNDLED); break;
            case 2: WallpaperEngine.setSource(this, WallpaperEngine.SOURCE_RANDOM); break;
            case 3:
                WallpaperEngine.setSource(this, WallpaperEngine.SOURCE_PHOTOS);
                if (WallpaperEngine.photos(this).isEmpty()) {
                    Toast.makeText(this, "Put .jpg or .png files in\n"
                            + WallpaperEngine.photosDir(this), Toast.LENGTH_LONG).show();
                }
                break;
            case 4:
                WallpaperEngine.setIntervalMinutes(this,
                        WallpaperEngine.nextInterval(WallpaperEngine.intervalMinutes(this)));
                break;
            case ROW_HINT:
                java.io.File dir = WallpaperEngine.photosDir(this);
                Toast.makeText(this, dir != null ? dir.getAbsolutePath() : "No external storage found",
                        Toast.LENGTH_LONG).show();
                return;
            default:
                return;
        }
        rebuild();
    }
}
