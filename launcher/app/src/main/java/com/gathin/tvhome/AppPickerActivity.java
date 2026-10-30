package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Opens from the "+" tile in the dock: tick the apps that should be pinned, in the order they are
 * ticked. Writes the same preferences AppAdapter reads ("tvhome"/"dock"), so returning to the
 * launcher shows the new dock.
 */
public class AppPickerActivity extends Activity {

    private static final String PREFS = "tvhome";
    private static final String PREF_DOCK = "dock";

    private final List<ResolveInfo> apps = new ArrayList<>();
    private final List<String> pinned = new ArrayList<>();
    private RowsAdapter adapter;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(PREF_DOCK, null);
        pinned.addAll(saved != null
                ? (saved.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(saved.split(","))))
                : new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.dock_apps))));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(96, 40, 96, 32);
        root.setBackgroundColor(0xF2000000);

        TextView title = new TextView(this);
        title.setText(R.string.add_app);
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setPadding(0, 0, 0, 8);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Tick the apps to pin them to the dock, in the order you tick them.");
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(15);
        hint.setPadding(0, 0, 0, 16);
        root.addView(hint);

        ListView list = new ListView(this);
        list.setSelector(R.drawable.list_selector);
        list.setDivider(null);
        root.addView(list);
        setContentView(root);

        adapter = new RowsAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> toggle(apps.get(position)));

        load();
    }

    private void load() {
        PackageManager pm = getPackageManager();
        Set<String> hidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        apps.clear();
        for (ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || hidden.contains(pkg)) continue;
            Intent main = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (main != null && main.getComponent() != null
                    && !main.getComponent().getClassName().equals(ri.activityInfo.name)) continue;
            apps.add(ri);
        }
        apps.sort((a, b) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
        adapter.notifyDataSetChanged();
    }

    private void toggle(ResolveInfo ri) {
        String pkg = ri.activityInfo.packageName;
        if (!pinned.remove(pkg)) {
            if (pinned.size() >= AppAdapter.MAX_DOCK) {
                Toast.makeText(this, "The dock holds " + AppAdapter.MAX_DOCK + " apps",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            pinned.add(pkg);
        }
        prefs.edit().putString(PREF_DOCK, String.join(",", pinned)).apply();
        adapter.notifyDataSetChanged();
    }

    private final class RowsAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int i) { return apps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            PackageManager pm = getPackageManager();
            ResolveInfo ri = apps.get(position);
            String pkg = ri.activityInfo.packageName;
            boolean on = pinned.contains(pkg);

            LinearLayout row = new LinearLayout(AppPickerActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(24, 14, 24, 14);
            row.setBackgroundResource(R.drawable.settings_row_bg);

            ImageView icon = new ImageView(AppPickerActivity.this);
            int size = (int) (24 * getResources().getDisplayMetrics().density);
            row.addView(icon, new LinearLayout.LayoutParams(size, size));
            icon.setImageDrawable(ri.loadIcon(pm));

            TextView label = new TextView(AppPickerActivity.this);
            label.setText(String.valueOf(ri.loadLabel(pm)));
            label.setTextSize(18);
            label.setTextColor(0xE6FFFFFF);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(18);
            row.addView(label, lp);

            TextView mark = new TextView(AppPickerActivity.this);
            mark.setText(on ? "✓" : "+");
            mark.setTextSize(20);
            mark.setTextColor(on ? 0xFFFFFFFF : 0x66FFFFFF);
            row.addView(mark);
            return row;
        }
    }
}
