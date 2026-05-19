package com.googletvperso.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;

/**
 * Google TV Perso — Lecteur vidéo VLC embarqué
 * Utilise TextureView + libvlc-all 3.6.x
 */
public class VlcPlayerActivity extends AppCompatActivity
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

    private LibVLC      libVLC;
    private MediaPlayer mediaPlayer;
    private Surface     vlcSurface;

    private TextureView textureView;
    private ProgressBar loadingView;
    private TextView    errorView;
    private View        controlsLayout;
    private Button      btnBack;
    private Button      btnPlay;
    private TextView    titleView;
    private TextView    liveBadge;
    private SeekBar     seekBar;
    private View        bottomBar;
    private TextView    timeCurrent;
    private TextView    timeTotal;

    private boolean isLive        = false;
    private boolean controlsShown = true;
    private boolean userSeeking   = false;
    private boolean surfaceReady  = false;
    private String  pendingUrl    = null;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int HIDE_DELAY = 4000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
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
        bottomBar      = findViewById(R.id.vlc_bottom_bar);
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

        initVlc();
        textureView.setSurfaceTextureListener(this);

        if (url != null && !url.isEmpty()) {
            pendingUrl = url.replaceFirst("(?i)^https://", "http://");
        } else {
            showError("URL manquante");
        }
    }

    private void initVlc() {
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--network-caching=2000");
        opts.add("--live-caching=2000");
        opts.add("--file-caching=2000");
        opts.add("--http-reconnect");
        opts.add("--no-drop-late-frames");
        opts.add("--no-skip-frames");
        opts.add("--avcodec-hw=any");

        libVLC      = new LibVLC(this, opts);
        mediaPlayer = new MediaPlayer(libVLC);

        mediaPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Playing:
                    runOnUiThread(() -> {
                        loadingView.setVisibility(View.GONE);
                        btnPlay.setText("⏸");
                        scheduleHide();
                    });
                    break;
                case MediaPlayer.Event.Paused:
                    runOnUiThread(() -> { btnPlay.setText("▶"); showControls(); });
                    break;
                case MediaPlayer.Event.Buffering:
                    final float buf = event.getBuffering();
                    runOnUiThread(() ->
                        loadingView.setVisibility(buf < 100f ? View.VISIBLE : View.GONE));
                    break;
                case MediaPlayer.Event.EncounteredError:
                    runOnUiThread(() -> showError("Erreur de lecture — vérifiez la connexion"));
                    break;
                case MediaPlayer.Event.EndReached:
                    runOnUiThread(() -> { btnPlay.setText("▶"); showControls(); });
                    break;
            }
        });
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int w, int h) {
        surfaceReady = true;
        vlcSurface   = new Surface(surfaceTexture);
        IVLCVout vout = mediaPlayer.getVLCVout();
        vout.setVideoSurface(vlcSurface, null);
        vout.setWindowSize(w, h);
        vout.attachViews();
        if (pendingUrl != null) {
            playUrl(pendingUrl);
            pendingUrl = null;
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        if (mediaPlayer != null) mediaPlayer.getVLCVout().setWindowSize(w, h);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        IVLCVout vout = mediaPlayer.getVLCVout();
        vout.detachViews();
        if (vlcSurface != null) { vlcSurface.release(); vlcSurface = null; }
        surfaceReady = false;
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    private void playUrl(String url) {
        try {
            Media media = new Media(libVLC, Uri.parse(url));
            media.addOption(":network-caching=2000");
            media.addOption(":http-reconnect=true");
            if (isLive) media.addOption(":live-caching=2000");
            mediaPlayer.setMedia(media);
            media.release();
            mediaPlayer.play();
            handler.postDelayed(progressUpdater, 500);
        } catch (Exception e) {
            showError("Impossible de lire : " + e.getMessage());
        }
    }

    private final Runnable progressUpdater = new Runnable() {
        @Override public void run() {
            if (mediaPlayer == null || isFinishing()) return;
            if (!userSeeking && !isLive) {
                float pos  = mediaPlayer.getPosition();
                long  time = mediaPlayer.getTime();
                long  len  = mediaPlayer.getLength();
                runOnUiThread(() -> {
                    seekBar.setProgress((int)(pos * 100));
                    timeCurrent.setText(fmt(time));
                    if (len > 0) timeTotal.setText(fmt(len));
                });
            }
            handler.postDelayed(this, 500);
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

    private void scheduleHide() {
        handler.removeCallbacksAndMessages("hide");
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            handler.postAtTime(() -> { if (!userSeeking) hideControls(); },
                "hide", android.os.SystemClock.uptimeMillis() + HIDE_DELAY);
        }
    }

    private void showError(String msg) {
        loadingView.setVisibility(View.GONE);
        errorView.setText("⚠ " + msg + "\n\nAppuyez sur Retour");
        errorView.setVisibility(View.VISIBLE);
        controlsLayout.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                finish(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!controlsShown) { showControls(); return true; }
                togglePlayPause(); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (mediaPlayer != null) mediaPlayer.pause(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (mediaPlayer != null) mediaPlayer.play(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!isLive && mediaPlayer != null) {
                    mediaPlayer.setTime(Math.min(mediaPlayer.getTime() + 10000, mediaPlayer.getLength()));
                    showControls();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!isLive && mediaPlayer != null) {
                    mediaPlayer.setTime(Math.max(mediaPlayer.getTime() - 10000, 0));
                    showControls();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showControls(); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onPause() {
        super.onPause();
        if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
    }

    @Override protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            try { mediaPlayer.getVLCVout().detachViews(); } catch (Exception ignored) {}
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (vlcSurface != null)  { vlcSurface.release();  vlcSurface  = null; }
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
        if (libVLC != null)      { libVLC.release();      libVLC      = null; }
    }
}
