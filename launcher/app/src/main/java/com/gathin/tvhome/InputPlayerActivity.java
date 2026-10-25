package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Shows an HDMI or AV input full screen, in a TvView. Fire OS keeps its own input player behind a permission
 * only Amazon's apps hold, and handing the input to its Live TV app opens whatever channel it played last, so
 * the launcher plays the input itself. Back returns to the launcher.
 */
public class InputPlayerActivity extends Activity {

    static final String EXTRA_INPUT_ID = "com.gathin.tvhome.INPUT_ID";
    static final String EXTRA_LABEL = "com.gathin.tvhome.INPUT_LABEL";
    private static final String TAG = "InputPlayer";

    private TvView tv;
    private TextView noSignal;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String id = getIntent().getStringExtra(EXTRA_INPUT_ID);
        if (id == null) {
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        tv = new TvView(this);
        root.addView(tv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        noSignal = new TextView(this);
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        noSignal.setText(getString(R.string.no_signal, label != null ? label : ""));
        noSignal.setTextColor(0xCCFFFFFF);
        noSignal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        noSignal.setTypeface(getResources().getFont(R.font.open_sans));
        noSignal.setVisibility(View.GONE);
        root.addView(noSignal, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);

        tv.setCallback(new TvView.TvInputCallback() {
            @Override
            public void onVideoAvailable(String inputId) {
                Log.i(TAG, "video available: " + inputId);
                noSignal.setVisibility(View.GONE);
            }

            @Override
            public void onVideoUnavailable(String inputId, int reason) {
                Log.i(TAG, "video unavailable: " + inputId + " reason " + reason);
                boolean tuning = reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING;
                noSignal.setVisibility(tuning ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onConnectionFailed(String inputId) {
                Log.w(TAG, "connection failed: " + inputId);
                noSignal.setVisibility(View.VISIBLE);
            }

            @Override
            public void onDisconnected(String inputId) {
                Log.w(TAG, "disconnected: " + inputId);
            }
        });
        Log.i(TAG, "tuning " + id);
        tv.tune(id, TvContract.buildChannelUriForPassthroughInput(id));
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the input (Home, Back, an app): release it rather than keep it playing in the background.
        if (tv != null) tv.reset();
        finish();
    }
}
