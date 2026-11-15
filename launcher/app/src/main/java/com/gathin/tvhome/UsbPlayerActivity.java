package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.text.format.DateFormat;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The USB screen's own player (ExoPlayer, the engine inside Just Player), for what Just Player can't be told
 * from outside: which audio to play. On every film and episode it picks
 * <ul>
 * <li>audio in the title's original language (TMDB), else English, else the file's default;</li>
 * <li>English subtitles, always on when there are any: SDH first (they also describe sounds), then a subtitle
 * file beside the video, then a full English track; a "forced" one (foreign lines only) only as a last resort;</li>
 * <li>whatever you changed last time on this file instead, if you changed it.</li>
 * </ul>
 * Sound: a boost up to 200% (Android's loudness effect, which limits the peaks so nothing clips), kept apart for
 * the TV speakers and for headphones, and a voice boost (dialogue frequencies up, loud scenes compressed). Audio
 * is always decoded here (no pass-through) so both effects apply on any output.
 * <p>
 * Remote: OK the controls (a TV overlay, see "the overlay"), ◀ ▶ 10 s, ▲ ▼ boost, ≡ the sound menu, Back stops. Files it can't play
 * (an audio format the TV can't decode, e.g. TrueHD) go to Just Player at the same point. Runs in the USB
 * screen's process and answers it like Just Player does ("position", "duration", "end_by").
 */
public class UsbPlayerActivity extends Activity {

    static final String EXTRA_PATH = "path", EXTRA_TITLE = "title", EXTRA_POSITION = "position",
            EXTRA_LANG = "lang", EXTRA_FALLBACK = "fallback", EXTRA_TMDB_ID = "tmdb_id", EXTRA_TMDB_KIND = "tmdb_kind",
            EXTRA_YEAR = "year", EXTRA_STORY = "story", EXTRA_RATING = "rating", EXTRA_SEASON = "season",
            EXTRA_EPISODE = "episode";

    private static final String TAG = "UsbPlayer";
    private static final String AUDIO_PREFS = "usb_audio", TRACK_PREFS = "usb_tracks";
    /** Boost steps as "how loud it sounds": +10 dB is heard as about twice as loud. */
    static final int[] BOOST_PERCENT = {100, 125, 150, 175, 200};
    private static final int[] BOOST_MB = {0, 320, 580, 810, 1000};
    private static final long SAVE_EVERY_MS = 30_000;

    private ExoPlayer player;
    private DefaultTrackSelector selector;
    /** 5.1/7.1 to stereo while an effect is on (see Downmix); passes audio through untouched otherwise. */
    private final Downmix downmix = new Downmix();
    private PlayerView view;
    private TextView badge, title, subtitle, clock, endsAt, elapsed, remaining, btnPlay;
    private View top, bottom, buttons;
    private ProgressBar timeline;
    private String episodeLine = "";
    private Overlay overlay = Overlay.HIDDEN;
    private AlertDialog dialog;
    private View pauseDim, pauseInfo;
    private TextView pauseTitle, pauseMeta, pauseStory, pauseDirector;
    private android.widget.LinearLayout pauseCast;
    private UsbInfo.Info info;
    private boolean pausePanelShown;
    private final android.util.LruCache<String, android.graphics.Bitmap> faces = new android.util.LruCache<>(16);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LoudnessEnhancer loudness;
    private DynamicsProcessing voice;
    private UsbLibrary.Video video;
    private String originalLang;
    private boolean tracksChosen, finishing, fellBack;
    /** What the rules picked this time (null when a remembered choice was used instead). */
    private String autoAudio, autoText;

    // ---- start ------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || !new File(path).isFile()) {
            finish();
            return;
        }
        video = new UsbLibrary.Video();
        video.path = path;
        video.file = new File(path).getName();
        originalLang = iso3(getIntent().getStringExtra(EXTRA_LANG));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_usb_player);
        bindOverlay();

        // Decode all audio here (stereo/multichannel PCM, no Dolby pass-through) so the boost and voice
        // effects reach every output. The TV decodes AC3, E-AC3 and DTS itself.
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this) {
            @Override
            protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
                return new DefaultAudioSink.Builder(context)
                        .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                        .setAudioProcessors(new AudioProcessor[]{downmix})
                        .build();
            }
        }.setEnableDecoderFallback(true);   // e.g. Dolby Vision files fall back to their HDR10/HEVC picture
        // Tunneled playback, as Just Player does on this TV: video goes straight to the TV's video chip, which
        // paces the frames itself (film cadence on the 60 Hz panel) instead of Android's 60 Hz compositor. Its
        // sound then takes a direct hardware path that audio effects can't reach, so tunneling is only on while
        // boost and voice boost are off (see updateTunneling).
        selector = new DefaultTrackSelector(this);
        boolean effects = effectsOn();
        downmix.wanted = effects;
        selector.setParameters(selector.buildUponParameters().setTunnelingEnabled(!effects));
        player = new ExoPlayer.Builder(this, renderers)
                .setTrackSelector(selector)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.addListener(listener);

        view.setPlayer(player);
        view.setUseController(false);
        view.setKeepScreenOn(true);
        SubtitleView subs = view.getSubtitleView();
        if (subs != null) {
            subs.setApplyEmbeddedFontSizes(false);
            subs.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.1f);
            subs.setStyle(new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null));
        }

        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.fromFile(new File(path)));
        item.setSubtitleConfigurations(sideSubtitles());
        player.setMediaItem(item.build(), getIntent().getLongExtra(EXTRA_POSITION, 0));
        player.prepare();
        player.play();

        attachEffects(player.getAudioSessionId());
        show(Overlay.TITLE);
        loadInfo();
        getSystemService(AudioManager.class).registerAudioDeviceCallback(outputs, handler);
        handler.postDelayed(saver, SAVE_EVERY_MS);
    }

    /** Subtitle files beside the video, English first; their language from the name or from the text. */
    private List<MediaItem.SubtitleConfiguration> sideSubtitles() {
        List<MediaItem.SubtitleConfiguration> out = new ArrayList<>();
        List<File> files = UsbLibrary.subtitles(video);
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            String name = f.getName().toLowerCase(Locale.ROOT);
            String mime = name.endsWith(".vtt") ? MimeTypes.TEXT_VTT
                    : name.endsWith(".ass") || name.endsWith(".ssa") ? MimeTypes.TEXT_SSA : MimeTypes.APPLICATION_SUBRIP;
            String lang = UsbLibrary.subtitleLanguage(f);
            if (lang == null) {
                Matcher m = NAME_LANG.matcher(name);
                if (m.find()) lang = m.group(1);
            }
            out.add(new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                    .setId("file:" + i)
                    .setMimeType(mime)
                    .setLanguage(lang)
                    .setLabel(f.getName())
                    .build());
        }
        return out;
    }

    private static final Pattern NAME_LANG = Pattern.compile("\\.([a-z]{2,3})\\.[a-z0-9]+$");

    // ---- choosing audio and subtitles -------------------------------------------------------------

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onTracksChanged(Tracks tracks) {
            if (!tracksChosen && !tracks.getGroups().isEmpty()) {
                tracksChosen = true;
                chooseTracks(tracks);
            }
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_ENDED) done(true);
            else refreshOverlay();
        }

        @Override
        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            refreshOverlay();
            handler.removeCallbacks(showPausePanel);
            if (!playWhenReady) {
                show(Overlay.FULL);   // paused: keep the controls up
                if (!isFinishing()) handler.postDelayed(showPausePanel, PAUSE_PANEL_DELAY_MS);
            } else {
                hidePausePanel();
                scheduleHide();
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            Log.w(TAG, "can't play " + video.path, error);
            fallBack();
        }

        @Override
        public void onAudioSessionIdChanged(int audioSessionId) {
            attachEffects(audioSessionId);
        }
    };

    private void chooseTracks(Tracks tracks) {
        TrackSelectionParameters.Builder p = player.getTrackSelectionParameters().buildUpon();
        SharedPreferences saved = getSharedPreferences(TRACK_PREFS, MODE_PRIVATE);

        // Audio
        Tracks.Group bestGroup = null;
        int bestIndex = -1, bestScore = Integer.MIN_VALUE;
        boolean anyAudio = false;
        String savedAudio = saved.getString("a2:" + video.path, null);
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            anyAudio = true;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                int score = key(f).equals(savedAudio) ? 1000 : audioScore(f);
                if (score > bestScore) {
                    bestScore = score;
                    bestGroup = g;
                    bestIndex = i;
                }
            }
        }
        if (anyAudio && bestGroup == null) {   // only audio the TV can't decode (TrueHD, …)
            fallBack();
            return;
        }
        String audioLang = "";
        if (bestGroup != null) {
            p.setOverrideForType(new TrackSelectionOverride(bestGroup.getMediaTrackGroup(), bestIndex));
            audioLang = iso3(bestGroup.getTrackFormat(bestIndex).language);
            autoAudio = savedAudio != null ? null : key(bestGroup.getTrackFormat(bestIndex));
        }

        // Subtitles
        String savedText = saved.getString("t2:" + video.path, null);
        Tracks.Group textGroup = null;
        int textIndex = -1, textScore = 0;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                int score = key(f).equals(savedText) ? 1000 : textScore(f);
                if (score > textScore) {
                    textScore = score;
                    textGroup = g;
                    textIndex = i;
                }
            }
        }
        boolean wantSubs = savedText == null || !savedText.equals("-");
        if (wantSubs && textGroup != null) {
            p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
            p.setOverrideForType(new TrackSelectionOverride(textGroup.getMediaTrackGroup(), textIndex));
        } else {
            p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        }
        if (savedText == null) autoText = wantSubs && textGroup != null ? key(textGroup.getTrackFormat(textIndex)) : "-";
        player.setTrackSelectionParameters(p.build());
        Log.i(TAG, "original=" + originalLang + " audio=" + audioLang + " subs=" + (wantSubs && textGroup != null
                ? textGroup.getTrackFormat(textIndex).label + "/" + textGroup.getTrackFormat(textIndex).language : "off"));
    }

    /** Original language first, then English, then the file's default; never a commentary. */
    private int audioScore(Format f) {
        String lang = iso3(f.language);
        String label = f.label != null ? f.label.toLowerCase(Locale.ROOT) : "";
        int s = 0;
        if (!originalLang.isEmpty() && lang.equals(originalLang)) s += 100;
        else if (lang.equals("eng")) s += 50;
        if ((f.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0) s += 5;
        if (label.contains("commentary") || (f.roleFlags & C.ROLE_FLAG_COMMENTARY) != 0) s -= 80;
        return s + Math.max(0, Math.min(f.channelCount, 8));
    }

    /** English: SDH, then a file beside the video, then a full track; "forced" (foreign lines only) last. */
    private static int textScore(Format f) {
        String lang = iso3(f.language);
        String label = f.label != null ? f.label.toLowerCase(Locale.ROOT) : "";
        boolean english = lang.equals("eng") || label.contains("english");
        boolean sideFile = isSideFile(f);
        boolean forced = isForced(f);
        boolean sdh = isSdh(f);
        if (english && forced) return 1;
        if (english && sdh) return 7;
        if (english && sideFile) return 6;
        if (english) return 5;
        if (sideFile && lang.isEmpty()) return 3;   // your own subtitle file, language unknown
        return 0;
    }

    private static boolean isSideFile(Format f) {
        return f.id != null && f.id.contains("file:");
    }

    private static boolean isForced(Format f) {
        String label = f.label != null ? f.label.toLowerCase(Locale.ROOT) : "";
        return (f.selectionFlags & C.SELECTION_FLAG_FORCED) != 0 || label.contains("forced");
    }

    /** Subtitles for the deaf and hard of hearing (they also describe sounds). */
    private static boolean isSdh(Format f) {
        String label = f.label != null ? f.label.toLowerCase(Locale.ROOT) : "";
        String name = isSideFile(f) ? label : "";
        return label.contains("sdh") || label.matches(".*\\bcc\\b.*") || label.contains("hearing")
                || name.matches(".*[ ._-](hi|sdh|cc)[ ._-].*")
                || (f.roleFlags & C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0;
    }

    /** What a track is called, to find it again next time: language and label. */
    private static String key(Format f) {
        return iso3(f.language) + "|" + (f.label != null ? f.label : "");
    }

    /**
     * Remembers the tracks you changed to on this file (only those: a track the rules picked is left to the rules,
     * so a rule change reaches it).
     */
    private void saveTracks() {
        if (player == null || !tracksChosen) return;
        String audio = null, text = "-";
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSelected(i)) continue;
                if (g.getType() == C.TRACK_TYPE_AUDIO) audio = key(g.getTrackFormat(i));
                else if (g.getType() == C.TRACK_TYPE_TEXT) text = key(g.getTrackFormat(i));
            }
        }
        SharedPreferences.Editor e = getSharedPreferences(TRACK_PREFS, MODE_PRIVATE).edit();
        if (audio != null && !audio.equals(autoAudio)) e.putString("a2:" + video.path, audio);
        else if (audio != null) e.remove("a2:" + video.path);
        if (!text.equals(autoText)) e.putString("t2:" + video.path, text);
        else e.remove("t2:" + video.path);
        e.apply();
    }

    /** ISO 639-2 terminology code ("jpn", "fra") for any form: "ja", "jpn", "fre", "en-US"; "" for none. */
    static String iso3(String code) {
        if (code == null) return "";
        String c = code.toLowerCase(Locale.ROOT).split("[-_]")[0];
        if (c.isEmpty() || c.equals("und") || c.equals("xx")) return "";
        if (c.equals("cn")) c = "zh";   // TMDB's code for Cantonese
        String t = BIBLIOGRAPHIC.get(c);
        if (t != null) return t;
        try {
            return new Locale(c).getISO3Language();
        } catch (MissingResourceException e) {
            return c;
        }
    }

    private static final Map<String, String> BIBLIOGRAPHIC = new HashMap<>();

    static {
        String[] pairs = {"fre", "fra", "ger", "deu", "chi", "zho", "dut", "nld", "cze", "ces", "gre", "ell",
                "rum", "ron", "slo", "slk", "per", "fas", "arm", "hye", "baq", "eus", "bur", "mya", "geo", "kat",
                "ice", "isl", "mac", "mkd", "mao", "mri", "may", "msa", "tib", "bod", "wel", "cym", "alb", "sqi"};
        for (int i = 0; i < pairs.length; i += 2) BIBLIOGRAPHIC.put(pairs[i], pairs[i + 1]);
    }

    // ---- boost and voice --------------------------------------------------------------------------

    private void attachEffects(int session) {
        releaseEffects();
        if (session == C.AUDIO_SESSION_ID_UNSET || session <= 0) return;
        try {
            loudness = new LoudnessEnhancer(session);
        } catch (RuntimeException e) {
            Log.w(TAG, "no loudness effect", e);
        }
        try {
            voice = new DynamicsProcessing(0, session, voiceConfig());
        } catch (RuntimeException e) {
            Log.w(TAG, "no dynamics effect", e);
        }
        applyEffects();
    }

    /**
     * Voice boost: lows down a little, the speech range (1-4 kHz) up, then one compressor that holds back loud
     * scenes and a limiter so nothing clips. Quiet dialogue ends up clearer against effects and music.
     */
    private static DynamicsProcessing.Config voiceConfig() {
        DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, 4);
        eq.setBand(0, new DynamicsProcessing.EqBand(true, 150f, -4f));
        eq.setBand(1, new DynamicsProcessing.EqBand(true, 1000f, 0f));
        eq.setBand(2, new DynamicsProcessing.EqBand(true, 4000f, 6f));
        eq.setBand(3, new DynamicsProcessing.EqBand(true, 20000f, 1f));
        DynamicsProcessing.Mbc mbc = new DynamicsProcessing.Mbc(true, true, 1);
        mbc.setBand(0, new DynamicsProcessing.MbcBand(true, 20000f, 5f, 150f, 3f, -26f, 6f, -90f, 1f, 0f, 4f));
        DynamicsProcessing.Limiter limiter = new DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -2f, 0f);
        return new DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, true, 4, true, 1, false, 0, true)
                .setPreEqAllChannelsTo(eq)
                .setMbcAllChannelsTo(mbc)
                .setLimiterAllChannelsTo(limiter)
                .build();
    }

    private void applyEffects() {
        int step = boostStep();
        try {
            if (loudness != null) {
                // + DOWNMIX_MB: makes up for the headroom the stereo downmix leaves (the effect limits peaks)
                loudness.setTargetGain(BOOST_MB[step] + (effectsOn() ? DOWNMIX_MB : 0));
                loudness.setEnabled(effectsOn());
            }
            if (voice != null) voice.setEnabled(voiceOn());
        } catch (RuntimeException e) {
            Log.w(TAG, "effects", e);
        }
        updateTunneling();
    }

    private boolean effectsOn() {
        return boostStep() > 0 || voiceOn();
    }

    /**
     * Audio effects only reach sound that goes through Android's stereo mixer. Tunneled sound, and 5.1/7.1 sound
     * (sent to the TV's audio chip directly), skip it. So: no effect on = tunneled and untouched (smoothest
     * picture); an effect on = not tunneled and mixed to stereo here, as the speakers and headphones get anyway.
     * Changing tunneling re-selects the tracks, which re-reads the downmix (a short pause).
     */
    private void updateTunneling() {
        if (selector == null) return;
        boolean effects = effectsOn();
        if (selector.getParameters().tunnelingEnabled == effects) {
            downmix.wanted = effects;
            selector.setParameters(selector.buildUponParameters().setTunnelingEnabled(!effects));
            Log.i(TAG, "tunneling " + !effects);
        }
    }

    /** Makes up for the downmix's headroom (below) in the loudness gain; that effect limits the peaks. */
    private static final int DOWNMIX_MB = 440;

    /**
     * 5.1 (FL FR FC LFE BL BR) or 7.1 (+ SL SR) to stereo, ITU-style and scaled down ~4.4 dB so loud scenes
     * can't clip; LFE left out, as Android's own downmix does. Whether it's on is read only when the audio
     * (re)starts, never mid-stream: changing tunneling re-selects the tracks, which restarts the audio.
     */
    static final class Downmix extends BaseAudioProcessor {
        volatile boolean wanted;
        private int channels;
        private static final float F = 0.6f, C_ = 0.42f, S = 0.42f;

        @Override
        protected AudioFormat onConfigure(AudioFormat in) throws UnhandledAudioFormatException {
            if (!wanted || in.encoding != C.ENCODING_PCM_16BIT || (in.channelCount != 6 && in.channelCount != 8)) {
                return AudioFormat.NOT_SET;   // pass through
            }
            channels = in.channelCount;
            return new AudioFormat(in.sampleRate, 2, C.ENCODING_PCM_16BIT);
        }

        @Override
        public void queueInput(ByteBuffer input) {
            input.order(ByteOrder.nativeOrder());
            int frames = input.remaining() / (2 * channels);
            ByteBuffer out = replaceOutputBuffer(frames * 4);
            for (int i = 0; i < frames; i++) {
                float fl = input.getShort(), fr = input.getShort(), fc = input.getShort();
                input.getShort();   // LFE
                float bl = input.getShort(), br = input.getShort();
                float sl = 0, sr = 0;
                if (channels == 8) {
                    sl = input.getShort();
                    sr = input.getShort();
                }
                out.putShort(clip(F * fl + C_ * fc + S * (bl + sl)));
                out.putShort(clip(F * fr + C_ * fc + S * (br + sr)));
            }
            out.flip();
        }

        private static short clip(float v) {
            return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
        }
    }

    private void releaseEffects() {
        if (loudness != null) loudness.release();
        if (voice != null) voice.release();
        loudness = null;
        voice = null;
    }

    private boolean onHeadphones() {
        return HomeRedirectService.headphonesConnected(this);
    }

    /** Boost step (index in BOOST_PERCENT) for the output in use. Headphones start from the Home boost. */
    private int boostStep() {
        SharedPreferences prefs = getSharedPreferences(AUDIO_PREFS, MODE_PRIVATE);
        if (onHeadphones()) {
            int db = HomeRedirectService.boostDb(this);
            int start = db >= 10 ? 4 : db >= 6 ? 2 : 0;
            return clampStep(prefs.getInt("boost_headphones", start));
        }
        return clampStep(prefs.getInt("boost_speakers", 0));
    }

    private void setBoostStep(int step) {
        step = clampStep(step);
        getSharedPreferences(AUDIO_PREFS, MODE_PRIVATE).edit()
                .putInt(onHeadphones() ? "boost_headphones" : "boost_speakers", step).apply();
        applyEffects();
        showBadge(getString(R.string.usb_boost_badge, BOOST_PERCENT[step],
                getString(onHeadphones() ? R.string.usb_headphones : R.string.usb_speakers)));
    }

    private static int clampStep(int s) {
        return Math.max(0, Math.min(BOOST_PERCENT.length - 1, s));
    }

    private boolean voiceOn() {
        return getSharedPreferences(AUDIO_PREFS, MODE_PRIVATE).getBoolean("voice", false);
    }

    private void setVoice(boolean on) {
        getSharedPreferences(AUDIO_PREFS, MODE_PRIVATE).edit().putBoolean("voice", on).apply();
        applyEffects();
        showBadge(getString(on ? R.string.usb_voice_on : R.string.usb_voice_off));
    }

    private final AudioDeviceCallback outputs = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            applyEffects();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            applyEffects();
        }
    };

    private void showBadge(String text) {
        badge.setText(text);
        badge.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideBadge);
        handler.postDelayed(hideBadge, 2000);
    }

    private final Runnable hideBadge = () -> badge.setVisibility(View.GONE);

    /** ≡ or the Sound button: boost, voice boost, Just Player. */
    private void soundMenu() {
        int step = boostStep();
        boolean on = voiceOn();
        String[] items = {
                getString(R.string.usb_boost_item, BOOST_PERCENT[step],
                        getString(onHeadphones() ? R.string.usb_headphones : R.string.usb_speakers)),
                getString(on ? R.string.usb_voice_item_on : R.string.usb_voice_item_off),
                getString(R.string.usb_open_just_player)};
        showDialog(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.usb_sound)
                .setItems(items, (d, which) -> {
                    if (which == 0) handler.post(this::boostMenu);
                    else if (which == 1) setVoice(!on);
                    else fallBack();
                }));
    }

    private void boostMenu() {
        String[] items = new String[BOOST_PERCENT.length];
        for (int i = 0; i < items.length; i++) items[i] = BOOST_PERCENT[i] + "%";
        showDialog(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.usb_boost_title,
                        getString(onHeadphones() ? R.string.usb_headphones : R.string.usb_speakers)))
                .setSingleChoiceItems(items, boostStep(), (d, which) -> {
                    setBoostStep(which);
                    d.dismiss();
                }));
    }

    // ---- remote -----------------------------------------------------------------------------------

    // ---- the overlay ------------------------------------------------------------------------------
    // HIDDEN: just the film. TITLE: title and clock for a moment (start). SEEK: the timeline while skipping.
    // FULL: everything plus the buttons (OK); stays while paused or a menu is open, else hides 6 s after the
    // last press.

    private enum Overlay { HIDDEN, TITLE, SEEK, FULL }

    private static final long HIDE_FULL_MS = 6000, HIDE_TITLE_MS = 3500, HIDE_SEEK_MS = 2500;

    private void bindOverlay() {
        view = findViewById(R.id.player);
        badge = findViewById(R.id.badge);
        top = findViewById(R.id.top);
        bottom = findViewById(R.id.bottom);
        buttons = findViewById(R.id.buttons);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        clock = findViewById(R.id.clock);
        endsAt = findViewById(R.id.endsAt);
        elapsed = findViewById(R.id.elapsed);
        remaining = findViewById(R.id.remaining);
        timeline = findViewById(R.id.timeline);
        btnPlay = findViewById(R.id.btnPlay);
        pauseDim = findViewById(R.id.pauseDim);
        pauseInfo = findViewById(R.id.pauseInfo);
        pauseTitle = findViewById(R.id.pauseTitle);
        pauseMeta = findViewById(R.id.pauseMeta);
        pauseStory = findViewById(R.id.pauseStory);
        pauseDirector = findViewById(R.id.pauseDirector);
        pauseCast = findViewById(R.id.pauseCast);

        // "VisionQuest  ·  S1 E8 Forever" (the USB screen's title): show name on top, episode below it
        String full = getIntent().getStringExtra(EXTRA_TITLE);
        if (full == null || full.isEmpty()) full = UsbLibrary.stripExt(video.file);
        int dot = full.indexOf("  ·  ");
        title.setText(dot > 0 ? full.substring(0, dot) : full);
        episodeLine = dot > 0 ? full.substring(dot + 5) : "";

        findViewById(R.id.btnBack).setOnClickListener(v -> skip(-10_000));
        findViewById(R.id.btnForward).setOnClickListener(v -> skip(10_000));
        btnPlay.setOnClickListener(v -> togglePlay());
        findViewById(R.id.btnAudio).setOnClickListener(v -> trackMenu(C.TRACK_TYPE_AUDIO));
        findViewById(R.id.btnSubtitles).setOnClickListener(v -> trackMenu(C.TRACK_TYPE_TEXT));
        findViewById(R.id.btnSound).setOnClickListener(v -> soundMenu());
        ViewGroup row = (ViewGroup) buttons;
        for (int i = 0; i < row.getChildCount(); i++) {
            View b = row.getChildAt(i);
            if (!b.isFocusable()) continue;
            b.setOnFocusChangeListener((v, has) ->
                    v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(120).start());
        }
    }

    private void show(Overlay o) {
        boolean wasFull = overlay == Overlay.FULL;
        if (o != Overlay.FULL) {
            handler.removeCallbacks(showPausePanel);
            hidePausePanel();
        }
        overlay = o;
        top.setVisibility(o == Overlay.TITLE || o == Overlay.FULL ? View.VISIBLE : View.GONE);
        bottom.setVisibility(o == Overlay.SEEK || o == Overlay.FULL ? View.VISIBLE : View.GONE);
        buttons.setVisibility(o == Overlay.FULL ? View.VISIBLE : View.GONE);
        SubtitleView subs = view.getSubtitleView();   // lines sit above the controls while they show
        if (subs != null) subs.setBottomPaddingFraction(o == Overlay.FULL ? 0.27f : o == Overlay.SEEK ? 0.16f
                : SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION);
        if (o == Overlay.FULL && !wasFull) btnPlay.requestFocus();   // always opens on Play
        handler.removeCallbacks(ticker);
        if (o != Overlay.HIDDEN) handler.post(ticker);
        scheduleHide();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hider);
        if (overlay == Overlay.HIDDEN || player == null) return;
        if (overlay == Overlay.FULL && (dialog != null || !player.getPlayWhenReady())) return;
        long delay = overlay == Overlay.FULL ? HIDE_FULL_MS : overlay == Overlay.TITLE ? HIDE_TITLE_MS : HIDE_SEEK_MS;
        handler.postDelayed(hider, delay);
    }

    private final Runnable hider = () -> show(Overlay.HIDDEN);

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshOverlay();
            if (overlay != Overlay.HIDDEN) handler.postDelayed(this, 500);
        }
    };

    private void refreshOverlay() {
        if (player == null || top == null) return;
        long pos = player.getCurrentPosition(), dur = player.getDuration();
        boolean known = dur != C.TIME_UNSET && dur > 0;
        elapsed.setText(UsbLibrary.clock(pos));
        remaining.setText(known ? "-" + UsbLibrary.clock(Math.max(0, dur - pos)) : "");
        timeline.setProgress(known ? (int) (pos * 10000 / dur) : 0);
        timeline.setSecondaryProgress(known ? (int) (Math.min(dur, player.getBufferedPosition()) * 10000 / dur) : 0);
        java.text.DateFormat time = DateFormat.getTimeFormat(this);
        long now = System.currentTimeMillis();
        clock.setText(time.format(new Date(now)));
        float speed = player.getPlaybackParameters().speed;
        endsAt.setText(known ? getString(R.string.usb_ends_at,
                time.format(new Date(now + (long) ((dur - pos) / (speed > 0 ? speed : 1f))))) : "");
        boolean paused = !player.getPlayWhenReady();
        String line = paused
                ? getString(R.string.usb_paused) + (episodeLine.isEmpty() ? "" : "  ·  " + episodeLine)
                : episodeLine;
        subtitle.setText(line);
        subtitle.setVisibility(line.isEmpty() ? View.GONE : pausePanelShown ? View.INVISIBLE : View.VISIBLE);
        btnPlay.setCompoundDrawablesRelativeWithIntrinsicBounds(
                paused ? R.drawable.ic_player_play : R.drawable.ic_player_pause, 0, 0, 0);
    }

    private void togglePlay() {
        if (player.getPlayWhenReady()) player.pause();
        else player.play();
        refreshOverlay();
    }

    private void skip(long ms) {
        long dur = player.getDuration();
        long to = Math.max(0, player.getCurrentPosition() + ms);
        if (dur != C.TIME_UNSET && dur > 0) to = Math.min(to, dur - 1000);
        player.seekTo(to);
        if (overlay != Overlay.FULL) show(Overlay.SEEK);
        else scheduleHide();
        refreshOverlay();
    }

    /** A menu over the player: the overlay stays up while it is open. */
    private void showDialog(AlertDialog.Builder b) {
        if (dialog != null) dialog.dismiss();
        handler.removeCallbacks(hider);
        dialog = b.show();
        dialog.setOnDismissListener(d -> {
            dialog = null;
            scheduleHide();
        });
    }

    // ---- pause screen -----------------------------------------------------------------------------
    // A moment after pausing, the picture dims and the story, details and cast (TMDB, loaded when the film
    // started, kept 30 days) show at the left. Play hides them at once.

    private static final long PAUSE_PANEL_DELAY_MS = 1500;

    private void loadInfo() {
        int id = getIntent().getIntExtra(EXTRA_TMDB_ID, 0);
        if (id <= 0) return;
        Trending.Item m = new Trending.Item();
        m.id = id;
        m.kind = getIntent().getStringExtra(EXTRA_TMDB_KIND);
        UsbInfo.load(this, getString(R.string.tmdb_token).trim(), m, getIntent().getIntExtra(EXTRA_SEASON, -1),
                getIntent().getIntExtra(EXTRA_EPISODE, -1), loaded -> {
                    if (isDestroyed()) return;
                    info = loaded;
                    if (pausePanelShown) fillPausePanel();
                    else if (loaded != null) for (UsbInfo.Person p : loaded.cast) preloadFace(p.photo);
                });
    }

    private final Runnable showPausePanel = () -> {
        if (player == null || player.getPlayWhenReady() || overlay != Overlay.FULL) return;
        fillPausePanel();
        if (pauseTitle.getText().length() == 0) return;
        pausePanelShown = true;
        title.setVisibility(View.INVISIBLE);      // the panel has the big title
        subtitle.setVisibility(View.INVISIBLE);
        pauseDim.setVisibility(View.VISIBLE);
        pauseInfo.setVisibility(View.VISIBLE);
        if (view.getSubtitleView() != null) view.getSubtitleView().setVisibility(View.INVISIBLE);
        pauseInfo.setTranslationX(-40 * getResources().getDisplayMetrics().density);
        pauseDim.animate().alpha(1f).setDuration(350).start();
        pauseInfo.animate().alpha(1f).translationX(0).setDuration(350).start();
    };

    private void hidePausePanel() {
        if (!pausePanelShown) return;
        pausePanelShown = false;
        title.setVisibility(View.VISIBLE);
        if (view.getSubtitleView() != null) view.getSubtitleView().setVisibility(View.VISIBLE);
        refreshOverlay();   // the episode line comes back
        pauseDim.animate().alpha(0f).setDuration(200).withEndAction(() -> pauseDim.setVisibility(View.GONE)).start();
        pauseInfo.animate().alpha(0f).setDuration(200).withEndAction(() -> pauseInfo.setVisibility(View.GONE)).start();
    }

    private void fillPausePanel() {
        int season = getIntent().getIntExtra(EXTRA_SEASON, -1), episode = getIntent().getIntExtra(EXTRA_EPISODE, -1);
        boolean isEpisode = season >= 0 && episode >= 0;
        pauseTitle.setText(title.getText());

        List<String> meta = new ArrayList<>();
        if (isEpisode) {
            String ep = getString(R.string.usb_episode, season, episode);
            String name = info != null && !info.episodeName.isEmpty() ? info.episodeName : "";
            meta.add(name.isEmpty() ? (episodeLine.isEmpty() ? ep : episodeLine) : ep + "  " + name);
        } else {
            String year = getIntent().getStringExtra(EXTRA_YEAR);
            if (year != null && !year.isEmpty()) meta.add(year);
        }
        if (info != null && !info.runtime.isEmpty()) meta.add(info.runtime);
        double rating = info != null && info.rating > 0 ? info.rating : getIntent().getDoubleExtra(EXTRA_RATING, 0);
        if (rating > 0) meta.add("★ " + String.format(Locale.US, "%.1f", rating));
        if (info != null && !info.genres.isEmpty()) meta.add(info.genres);
        pauseMeta.setText(String.join("  ·  ", meta));

        String story = info != null && !info.episodeStory.isEmpty() ? info.episodeStory : getIntent().getStringExtra(EXTRA_STORY);
        pauseStory.setText(story != null ? story : "");
        pauseStory.setVisibility(story == null || story.isEmpty() ? View.GONE : View.VISIBLE);

        String by = info != null ? info.director : "";
        boolean createdBy = isEpisode && info != null && info.episodeStory.isEmpty();
        pauseDirector.setText(by.isEmpty() ? "" : getString(createdBy ? R.string.usb_created_by : R.string.usb_directed_by, by));
        pauseDirector.setVisibility(by.isEmpty() ? View.GONE : View.VISIBLE);

        pauseCast.removeAllViews();
        if (info == null) return;
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < Math.min(6, info.cast.size()); i++) {
            UsbInfo.Person p = info.cast.get(i);
            android.widget.LinearLayout col = new android.widget.LinearLayout(this);
            col.setOrientation(android.widget.LinearLayout.VERTICAL);
            col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            android.widget.ImageView photo = new android.widget.ImageView(this);
            photo.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            photo.setBackgroundColor(0x33FFFFFF);
            photo.setOutlineProvider(UsbActivity.ROUND);
            photo.setClipToOutline(true);
            int size = Math.round(60 * d);
            col.addView(photo, new android.widget.LinearLayout.LayoutParams(size, size));
            col.addView(castText(p.name, 12, 0xF2FFFFFF, Math.round(6 * d)));
            if (!p.role.isEmpty()) col.addView(castText(p.role, 11, 0x99FFFFFF, Math.round(1 * d)));
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(Math.round(92 * d), -2);
            if (i > 0) lp.setMarginStart(Math.round(10 * d));
            pauseCast.addView(col, lp);
            setFace(p.photo, photo);
        }
    }

    private TextView castText(String text, int sp, int color, int marginTop) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTypeface(pauseStory.getTypeface());
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = marginTop;
        t.setLayoutParams(lp);
        return t;
    }

    private void preloadFace(String path) {
        if (path == null || path.isEmpty() || faces.get(path) != null) return;
        Trending.face(path, bmp -> {
            if (bmp != null) faces.put(path, bmp);
        });
    }

    private void setFace(String path, android.widget.ImageView into) {
        if (path == null || path.isEmpty()) return;
        android.graphics.Bitmap b = faces.get(path);
        if (b != null) {
            into.setImageBitmap(b);
            return;
        }
        Trending.face(path, bmp -> {
            if (bmp == null || isDestroyed()) return;
            faces.put(path, bmp);
            into.setImageBitmap(bmp);
        });
    }

    // ---- Audio and Subtitles menus ----------------------------------------------------------------

    /** Every track of one kind, named plainly ("Japanese · 5.1 · AAC", "English · SDH"), the playing one checked. */
    private void trackMenu(int type) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        int checked = -1;
        if (type == C.TRACK_TYPE_TEXT) {
            labels.add(getString(R.string.usb_off));
            actions.add(() -> player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()));
        }
        int fixed = labels.size();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                if (g.isTrackSelected(i)) checked = labels.size();
                labels.add(trackLabel(g.getTrackFormat(i), type));
                final Tracks.Group group = g;
                final int index = i;
                actions.add(() -> player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), index))
                        .build()));
            }
        }
        if (labels.size() == fixed) {
            showBadge(getString(R.string.usb_no_tracks));
            return;
        }
        if (checked < 0 && type == C.TRACK_TYPE_TEXT) checked = 0;
        showDialog(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(type == C.TRACK_TYPE_AUDIO ? R.string.usb_audio : R.string.usb_subtitles)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, which) -> {
                    actions.get(which).run();
                    d.dismiss();
                }));
    }

    private String trackLabel(Format f, int type) {
        List<String> parts = new ArrayList<>();
        String lang = languageName(f.language);
        String label = f.label != null ? f.label.trim() : "";
        String low = label.toLowerCase(Locale.ROOT);
        boolean sideFile = isSideFile(f);
        if (lang != null) parts.add(lang);
        else if (!label.isEmpty() && !sideFile && label.length() <= 30) parts.add(label);
        else parts.add(getString(R.string.usb_unknown_language));
        if (type == C.TRACK_TYPE_AUDIO) {
            int ch = f.channelCount;
            if (ch == 1) parts.add("Mono");
            else if (ch == 2) parts.add("Stereo");
            else if (ch == 6) parts.add("5.1");
            else if (ch == 8) parts.add("7.1");
            else if (ch > 0) parts.add(ch + " ch");
            String codec = codecName(f.sampleMimeType);
            if (codec != null) parts.add(codec);
            if (low.contains("commentary") || (f.roleFlags & C.ROLE_FLAG_COMMENTARY) != 0) {
                parts.add(getString(R.string.usb_commentary));
            }
        } else {
            if (isSdh(f)) parts.add("SDH");
            if (isForced(f)) parts.add(getString(R.string.usb_forced));
            if (sideFile) parts.add(getString(R.string.usb_your_file));
        }
        // Only labels that say something the rest doesn't ("Signs & Songs", "Director's commentary"); most just
        // repeat the language in its own script ("العربية", "Čeština") or name the release.
        if (!sideFile && low.matches(".*(sign|song|director|description|descriptive|dub|original).*")
                && !low.contains("|") && label.length() <= 32) {
            parts.add(label);
        }
        return String.join("  ·  ", parts);
    }

    private static String languageName(String code) {
        if (code == null || code.isEmpty() || code.equals("und")) return null;
        String name = Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH);
        return name.isEmpty() || name.equalsIgnoreCase(code) ? null : name;
    }

    private static String codecName(String mime) {
        if (mime == null) return null;
        switch (mime) {
            case MimeTypes.AUDIO_AC3: return "Dolby Digital";
            case MimeTypes.AUDIO_E_AC3: return "Dolby Digital Plus";
            case MimeTypes.AUDIO_E_AC3_JOC: return "Dolby Atmos";
            case MimeTypes.AUDIO_TRUEHD: return "Dolby TrueHD";
            case MimeTypes.AUDIO_DTS:
            case MimeTypes.AUDIO_DTS_HD:
            case MimeTypes.AUDIO_DTS_EXPRESS: return "DTS";
            case MimeTypes.AUDIO_AAC: return "AAC";
            case MimeTypes.AUDIO_OPUS: return "Opus";
            case MimeTypes.AUDIO_FLAC: return "FLAC";
            case MimeTypes.AUDIO_MPEG: return "MP3";
            default: return null;
        }
    }

    // ---- remote -----------------------------------------------------------------------------------
    // Overlay hidden: OK shows it (never pauses), ◀ ▶ skip 10 s (held: 30 s steps), ▲ ▼ boost, Back stops.
    // Overlay up: ◀ ▶ move between the buttons, OK presses one, Back hides it. Any time: ≡ the Sound menu,
    // ⏯ ⏪ ⏩ on the remote play/pause and skip 30 s.

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (player == null) return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && e.getRepeatCount() == 0;
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (first) togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (first) player.play();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (first) player.pause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) skip(30_000);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) skip(-30_000);
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (e.getAction() == KeyEvent.ACTION_UP) soundMenu();
                return true;
            default:
        }
        if (overlay == Overlay.FULL) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (!down) show(Overlay.HIDDEN);
                return true;
            }
            if (down) scheduleHide();   // any press keeps it up
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) return true;   // one row
            return super.dispatchKeyEvent(e);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (first) show(Overlay.FULL);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    int r = e.getRepeatCount();
                    long step = r == 0 ? 10_000 : r % 3 == 0 ? 30_000 : 0;   // held: 30 s every few repeats
                    if (step > 0) skip(code == KeyEvent.KEYCODE_DPAD_RIGHT ? step : -step);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (first) setBoostStep(boostStep() + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (first) setBoostStep(boostStep() - 1);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    @Override
    public void onBackPressed() {
        done(false);
    }

    // ---- ending -----------------------------------------------------------------------------------

    private final Runnable saver = new Runnable() {
        @Override
        public void run() {
            savePosition(false);
            handler.postDelayed(this, SAVE_EVERY_MS);
        }
    };

    private void savePosition(boolean completed) {
        if (player == null || player.getPlaybackState() == Player.STATE_IDLE && !completed) return;
        long dur = player.getDuration();
        UsbLibrary.savePosition(this, video, player.getCurrentPosition(), dur == C.TIME_UNSET ? 0 : dur, completed);
    }

    /** Back or the end: answer the USB screen the way Just Player does. */
    private void done(boolean completed) {
        if (finishing || player == null) {
            finish();
            return;
        }
        finishing = true;
        saveTracks();
        long dur = player.getDuration();
        Intent result = new Intent()
                .putExtra("position", (int) player.getCurrentPosition())
                .putExtra("duration", dur == C.TIME_UNSET ? 0 : (int) dur)
                .putExtra("end_by", completed ? "playback_completion" : "user");
        setResult(RESULT_OK, result);
        finish();
    }

    /** Can't play it here: hand the file to Just Player at the same point (the USB screen opens it). */
    private void fallBack() {
        if (finishing || fellBack) return;
        fellBack = finishing = true;
        Toast.makeText(this, R.string.usb_fallback, Toast.LENGTH_LONG).show();
        long pos = player != null ? player.getCurrentPosition() : getIntent().getLongExtra(EXTRA_POSITION, 0);
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_FALLBACK, true).putExtra("position", (int) pos));
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Home during a film: keep the place (the USB tile reopens the list, not the paused film)
        if (player != null) {
            if (!fellBack) savePosition(false);
            saveTracks();
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            getSystemService(AudioManager.class).unregisterAudioDeviceCallback(outputs);
            releaseEffects();
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
