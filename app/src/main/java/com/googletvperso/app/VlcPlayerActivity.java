package com.googletvperso.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;

/**
 * Google TV Perso — Lecteur VLC embarqué
 *
 * Extends Activity (NOT AppCompatActivity) pour éviter le crash
 * "You need to use a Theme.AppCompat theme" avec le thème fullscreen.
 */
public class VlcPlayerActivity extends Activity
        implements TextureView.SurfaceTextureListener {

    public static final String EXTRA_URL     = "url";
    public static final String EXTRA_TITLE   = "title";
    public static final String EXTRA_IS_LIVE = "is_live";

    public static void start(Context ctx, String url, String title, boolean isLive) {
        Intent i = new Intent(ctx, VlcPlayerActivity.class);
        i.putExtra(EXTRA_URL,     url);
        i.putExtra(EXTRA_TITLE,   title != null ? title : "");
        i.putExtra(EXTRA_IS_LIVE, isLive);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    // ── VLC ─────────────────────────────────────────────────────────
    private LibVLC      libVLC;
    private MediaPlayer mediaPlayer;
    private Surface     vlcSurface;
    private boolean     viewsAttached = false;

    // ── Views ────────────────────────────────────────────────────────
    private TextureView textureView;
    private ProgressBar loadingView;
    private TextView    errorView;
    private View        controlsLayout;
    private TextView    btnBack;
    private TextView    btnPlay;
    private TextView    titleView;
    private TextView    liveBadge;
    private SeekBar     seekBar;
    private TextView    timeCurrent;
    private TextView    timeTotal;

    // ── State ────────────────────────────────────────────────────────
    private boolean isLive        = false;
    private boolean controlsShown = true;
    private boolean userSeeking   = false;
    private String  pendingUrl    = null;

    private final Handler  handler    = new Handler(Looper.getMainLooper());
    private final Object   hideToken  = new Object();
    private static final int HIDE_DELAY = 4000;

    // ════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );

        setContentView(R.layout.activity_vlc_player);

        String url  = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        isLive       = getIntent().getBooleanExtra(EXTRA_IS_LIVE, false);

        textureView    = findViewById(R.id.vlc_surface);
        loadingView    = findViewById(R.id.vlc_loading);
        errorView      = findViewById(R.id.vlc_error);
        controlsLayout = findViewById(R.id.vlc_controls);
        btnBack        = findViewById(R.id.vlc_btn_back);
        btnPlay        = findViewById(R.id.vlc_btn_play);
        titleView      = findViewById(R.id.vlc_title);
        liveBadge      = findViewById(R.id.vlc_live_badge);
        seekBar        = findViewById(R.id.vlc_seekbar);
        timeCurrent    = findViewById(R.id.vlc_time_current);
        timeTotal      = findViewById(R.id.vlc_time_total);

        if (title != null && !title.isEmpty()) titleView.setText(title);

        if (isLive) {
            liveBadge.setVisibility(View.VISIBLE);
            seekBar.setVisibility(View.GONE);
            timeCurrent.setVisibility(View.GONE);
            timeTotal.setVisibility(View.GONE);
        }

        btnBack.setOnClickListener(v -> finish());
        btnPlay.setOnClickListener(v -> togglePlayPause());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (mediaPlayer != null) mediaPlayer.setPosition(sb.getProgress() / 100f);
                userSeeking = false;
                scheduleHide();
            }
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {}
        });

        textureView.setOnClickListener(v -> toggleControls());
        controlsLayout.setOnClickListener(v -> toggleControls());

        // Style cinématique des boutons (pas de halo jaune Android)
        setupPlayerButtons();

        // Initialiser VLC AVANT d'enregistrer le listener TextureView
        initVlc();
        textureView.setSurfaceTextureListener(this);

        if (url != null && !url.isEmpty()) {
            // Toujours HTTP : goldenlink.live ne supporte pas HTTPS
            pendingUrl = url.replaceFirst("(?i)^https://", "http://");
        } else {
            showError("URL manquante");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Réappliquer le plein écran si retour depuis le fond
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
    }

    // ════════════════════════════════════════════════════════════════
    //  VLC INIT & RELEASE
    // ════════════════════════════════════════════════════════════════

    private void initVlc() {
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--network-caching=3000");
        opts.add("--live-caching=3000");
        opts.add("--file-caching=1500");
        opts.add("--http-reconnect");
        opts.add("--no-drop-late-frames");
        opts.add("--no-skip-frames");
        // Ne pas forcer le hardware decode : laisse VLC choisir
        // (--avcodec-hw=any peut crasher sur certains chipsets TV)

        libVLC      = new LibVLC(this, opts);
        mediaPlayer = new MediaPlayer(libVLC);

        mediaPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Playing:
                    runOnUiThread(() -> {
                        loadingView.setVisibility(View.GONE);
                        errorView.setVisibility(View.GONE);
                        // Masquer le bouton centre (même comportement que le player VOD :
                        // le play-overlay n'est visible que quand la vidéo est pausée).
                        btnPlay.setVisibility(View.GONE);
                        scheduleHide();
                        handler.post(progressUpdater);
                    });
                    break;
                case MediaPlayer.Event.Paused:
                    runOnUiThread(() -> {
                        handler.removeCallbacks(progressUpdater);
                        // Montrer le bouton centre avec icône de lecture (non-emoji)
                        btnPlay.setVisibility(View.VISIBLE);
                        showControls();
                    });
                    break;
                case MediaPlayer.Event.Buffering:
                    final float buf = event.getBuffering();
                    runOnUiThread(() ->
                        loadingView.setVisibility(buf < 100f ? View.VISIBLE : View.GONE));
                    break;
                case MediaPlayer.Event.EncounteredError:
                    runOnUiThread(() -> {
                        handler.removeCallbacks(progressUpdater);
                        showError("Erreur de lecture — vérifiez la connexion réseau");
                    });
                    break;
                case MediaPlayer.Event.EndReached:
                    runOnUiThread(() -> {
                        handler.removeCallbacks(progressUpdater);
                        btnPlay.setVisibility(View.VISIBLE);
                        showControls();
                    });
                    break;
                case MediaPlayer.Event.Stopped:
                    runOnUiThread(() -> handler.removeCallbacks(progressUpdater));
                    break;
            }
        });
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.setEventListener(null);
            mediaPlayer.stop();
            if (viewsAttached) {
                try { mediaPlayer.getVLCVout().detachViews(); } catch (Exception ignored) {}
                viewsAttached = false;
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (vlcSurface != null) { vlcSurface.release(); vlcSurface = null; }
        if (libVLC != null)     { libVLC.release();     libVLC     = null; }
    }

    // ════════════════════════════════════════════════════════════════
    //  SURFACE TEXTURE CALLBACKS
    // ════════════════════════════════════════════════════════════════

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int w, int h) {
        if (mediaPlayer == null) return;
        vlcSurface = new Surface(surfaceTexture);
        IVLCVout vout = mediaPlayer.getVLCVout();
        vout.setVideoSurface(vlcSurface, null);
        vout.setWindowSize(w, h);
        vout.attachViews();
        viewsAttached = true;
        if (pendingUrl != null) {
            playUrl(pendingUrl);
            pendingUrl = null;
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        if (mediaPlayer != null && viewsAttached) {
            mediaPlayer.getVLCVout().setWindowSize(w, h);
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        // Retourner FALSE : ne pas libérer le SurfaceTexture immédiatement.
        // VLC pourrait encore rendre une frame ; la libération se fait dans onDestroy.
        if (mediaPlayer != null && viewsAttached) {
            try { mediaPlayer.getVLCVout().detachViews(); } catch (Exception ignored) {}
            viewsAttached = false;
        }
        if (vlcSurface != null) { vlcSurface.release(); vlcSurface = null; }
        return false;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    // ════════════════════════════════════════════════════════════════
    //  PLAYBACK
    // ════════════════════════════════════════════════════════════════

    private void playUrl(String url) {
        if (mediaPlayer == null) return;
        try {
            loadingView.setVisibility(View.VISIBLE);
            errorView.setVisibility(View.GONE);
            Media media = new Media(libVLC, Uri.parse(url));
            media.addOption(":network-caching=3000");
            media.addOption(":http-reconnect=true");
            if (isLive) {
                media.addOption(":live-caching=3000");
                media.addOption(":clock-jitter=0");
                media.addOption(":clock-synchro=0");
            }
            mediaPlayer.setMedia(media);
            media.release();
            mediaPlayer.play();
        } catch (Exception e) {
            showError("Impossible de lire : " + e.getMessage());
        }
    }

    private final Runnable progressUpdater = new Runnable() {
        @Override public void run() {
            if (mediaPlayer == null || isFinishing() || isLive) return;
            if (!userSeeking) {
                float pos  = mediaPlayer.getPosition();
                long  time = mediaPlayer.getTime();
                long  len  = mediaPlayer.getLength();
                seekBar.setProgress((int)(pos * 100));
                timeCurrent.setText(fmt(time));
                if (len > 0) timeTotal.setText(fmt(len));
            }
            handler.postDelayed(this, 1000);
        }
    };

    private String fmt(long ms) {
        if (ms <= 0) return "00:00";
        long s = ms / 1000, m = s / 60, h = m / 60;
        return h > 0
            ? String.format("%d:%02d:%02d", h, m % 60, s % 60)
            : String.format("%02d:%02d", m % 60, s % 60);
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) mediaPlayer.pause(); else mediaPlayer.play();
    }

    // ════════════════════════════════════════════════════════════════
    //  CONTROLS UI
    // ════════════════════════════════════════════════════════════════

    private void toggleControls() {
        if (controlsShown) hideControls(); else showControls();
    }

    private void showControls() {
        controlsShown = true;
        controlsLayout.setVisibility(View.VISIBLE);
        scheduleHide();
    }

    private void hideControls() {
        controlsShown = false;
        controlsLayout.setVisibility(View.GONE);
    }

    // ── Boutons cinématiques : cercle / pilule via GradientDrawable ──
    // Aucun widget Button → zéro halo jaune Android TV.

    private void setupPlayerButtons() {
        // Désactiver le halo focus jaune système (API 26+)
        if (Build.VERSION.SDK_INT >= 26) {
            btnPlay.setDefaultFocusHighlightEnabled(false);
            btnBack.setDefaultFocusHighlightEnabled(false);
        }

        // Style initial des boutons
        setPlayButtonStyle(false);
        setBackButtonStyle(false);

        btnBack.setOnFocusChangeListener((v, focused) -> setBackButtonStyle(focused));
    }

    private void setPlayButtonStyle(boolean focused) {
        // Bouton bleu Google, identique au .play-overlay du player VOD (player.css)
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        if (focused) {
            d.setColor(0xFF4285F4);                      // bleu plein au focus
            d.setStroke(dp(3), 0xFFFFFFFF);              // anneau blanc
            btnPlay.setAlpha(1f);
        } else {
            d.setColor(0xE04285F4);                      // rgba(66,133,244,.88) — copie player.css
            d.setStroke(0, 0);
            btnPlay.setAlpha(1f);
        }
        // Box-shadow simulé : impossible en Java natif, compensé par elevation
        btnPlay.setElevation(dp(12));
        btnPlay.setBackground(d);
    }

    private void setBackButtonStyle(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(24));
        if (focused) {
            d.setColor(0xFFFFFFFF);
            btnBack.setTextColor(0xFF111111);
        } else {
            d.setColor(0x44FFFFFF);
            btnBack.setTextColor(0xFFFFFFFF);
        }
        btnBack.setBackground(d);
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }

    private void scheduleHide() {
        handler.removeCallbacksAndMessages(hideToken);
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            handler.postAtTime(
                () -> { if (!userSeeking) hideControls(); },
                hideToken,
                android.os.SystemClock.uptimeMillis() + HIDE_DELAY
            );
        }
    }

    private void showError(String msg) {
        loadingView.setVisibility(View.GONE);
        errorView.setText("⚠ " + msg + "\n\nAppuyez sur Retour pour quitter");
        errorView.setVisibility(View.VISIBLE);
        controlsLayout.setVisibility(View.VISIBLE);
    }

    // ════════════════════════════════════════════════════════════════
    //  D-PAD / KEYS
    // ════════════════════════════════════════════════════════════════

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;

            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!controlsShown) { showControls(); return true; }
                togglePlayPause();
                return true;

            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (mediaPlayer != null) mediaPlayer.pause();
                return true;

            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (mediaPlayer != null) mediaPlayer.play();
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!isLive && mediaPlayer != null) {
                    long target = Math.min(mediaPlayer.getTime() + 10000, mediaPlayer.getLength() - 500);
                    mediaPlayer.setTime(Math.max(0, target));
                    showControls();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!isLive && mediaPlayer != null) {
                    mediaPlayer.setTime(Math.max(0, mediaPlayer.getTime() - 10000));
                    showControls();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showControls();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
