package com.googletvperso.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.fragment.app.FragmentActivity;

/**
 * Google TV Perso — Activité principale / Launcher
 *
 * Charge l'interface Google TV depuis les assets (file:///android_asset/index.html).
 * Les touches D-pad sont relayées au JS comme événements CustomEvent.
 * La clé HOME lance l'overlay sidebar (équivalent du menu Google TV).
 */
public class HomeActivity extends FragmentActivity {

    private static final String HOME_URL = "file:///android_asset/index.html";

    private WebView webView;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Plein écran immersif sans barre système
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        getWindow().getDecorView().setSystemUiVisibility(flags);

        setContentView(R.layout.activity_home);
        webView = findViewById(R.id.webView);

        configureWebView();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setTextZoom(100);            // empêche le zoom système des polices TV
        ws.setLoadWithOverviewMode(false); // pas de zoom-out automatique avec viewport fixe

        // UA Android TV pour que l'app JS détecte la TV
        String ua = ws.getUserAgentString().replace("Mobile", "TV");
        ws.setUserAgentString(ua + " AndroidTV GoogleTVPerso/1.0");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new TvBridge(this), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url    = req.getUrl().toString();
                String scheme = req.getUrl().getScheme();

                // Intent scheme (player.js peut déclencher intent:// pour VLC externe)
                if ("intent".equals(scheme)) {
                    try {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        String httpUrl = url.replaceFirst("^intent:", "").split("#")[0];
                        openVideoIntent(httpUrl);
                    }
                    return true;
                }

                // URLs vidéo directes
                if (isVideoUrl(url)) {
                    openVideoIntent(url);
                    return true;
                }

                // Assets locaux → laisser passer
                if (url.startsWith("file:///android_asset/")) return false;

                // GitHub Pages → laisser passer
                if (url.startsWith("https://morpheus45.github.io")) return false;

                // Autres URL → navigateur externe
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Indiquer au JS qu'on est dans le launcher natif Android TV
                view.evaluateJavascript("window.GOOGLE_TV_NATIVE='android_tv';", null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            private View customView;

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                customView = view;
                webView.setVisibility(View.GONE);
                setContentView(view);
            }

            @Override
            public void onHideCustomView() {
                setContentView(R.layout.activity_home);
                webView = findViewById(R.id.webView);
                webView.setVisibility(View.VISIBLE);
                customView = null;
            }
        });
    }

    // ── D-PAD : relayer les touches au JS comme CustomEvent ──────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        String jsEvent = null;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:        jsEvent = "tv_up";    break;
            case KeyEvent.KEYCODE_DPAD_DOWN:      jsEvent = "tv_down";  break;
            case KeyEvent.KEYCODE_DPAD_LEFT:      jsEvent = "tv_left";  break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:     jsEvent = "tv_right"; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:   jsEvent = "tv_enter"; break;
            case KeyEvent.KEYCODE_BACK:           jsEvent = "tv_back";  break;
            case KeyEvent.KEYCODE_MENU:           jsEvent = "tv_menu";  break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:    jsEvent = "tv_playpause"; break;
        }

        if (jsEvent != null) {
            final String evt = jsEvent;
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('" + evt + "'));", null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // Toujours relayer BACK au JS — il gère le comportement (fermer overlay, etc.)
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('tv_back'));", null);
    }

    // ── Lecteur système fallback ─────────────────────────────────────────────

    public void openVideoIntent(String url) {
        String httpUrl = url.replaceFirst("(?i)^https://", "http://");
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(httpUrl), "video/*");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(httpUrl))); }
            catch (Exception ignored) {}
        }
    }

    private boolean isVideoUrl(String url) {
        if (url == null) return false;
        String lo = url.toLowerCase();
        return lo.contains("goldenlink.live") && (
               lo.endsWith(".mkv") || lo.endsWith(".mp4") ||
               lo.endsWith(".avi") || lo.endsWith(".ts")  ||
               lo.endsWith(".m3u8") || lo.contains("/movie/") ||
               lo.contains("/series/") || lo.contains("/live/"));
    }

    // ── Cycle de vie ─────────────────────────────────────────────────────────

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        webView.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Réappliquer le plein écran si retour depuis le lecteur
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    // ── Bridge JavaScript ↔ Java ─────────────────────────────────────────────

    static class TvBridge {
        private final HomeActivity act;
        TvBridge(HomeActivity a) { act = a; }

        /** Ouvre l'URL dans le lecteur VLC embarqué */
        @JavascriptInterface
        public void openInVlc(String url, String title, boolean isLive) {
            act.runOnUiThread(() ->
                VlcPlayerActivity.start(act, url, title, isLive)
            );
        }

        /** Fallback lecteur système */
        @JavascriptInterface
        public void openVideo(String url, String title) {
            act.runOnUiThread(() -> act.openVideoIntent(url));
        }

        /** Type d'appareil */
        @JavascriptInterface
        public String getDeviceType() { return "android_tv"; }

        /** Requête HTTP depuis Java (pas de restriction mixed content) */
        @JavascriptInterface
        public String fetchJson(String url) {
            java.net.HttpURLConnection conn = null;
            try {
                java.net.URL u = new java.net.URL(url.replace("https://", "http://"));
                conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "GoogleTVPerso/1.0");
                conn.connect();
                if (conn.getResponseCode() != 200) return null;
                java.io.InputStream is = conn.getInputStream();
                java.util.Scanner sc = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
                return sc.hasNext() ? sc.next() : "";
            } catch (Exception e) {
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        /** Affiche un toast système */
        @JavascriptInterface
        public void showToast(String msg) {
            act.runOnUiThread(() ->
                Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
            );
        }

        /** Recharge l'interface */
        @JavascriptInterface
        public void reloadApp() {
            act.runOnUiThread(() -> act.webView.loadUrl(HomeActivity.HOME_URL));
        }

        /** Version de l'APK installée */
        @JavascriptInterface
        public String getApkVersion() {
            return String.valueOf(BuildConfig.VERSION_CODE);
        }

        /** Vérifie les mises à jour GitHub de façon asynchrone.
         *  Appelle window._onUpdateResult(data) quand terminé. */
        @JavascriptInterface
        public void checkUpdateAsync() {
            new Thread(() -> {
                java.net.HttpURLConnection conn = null;
                try {
                    java.net.URL u = new java.net.URL(
                        "https://api.github.com/repos/morpheus45/google-tv-perso/releases/latest");
                    conn = (java.net.HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("User-Agent", "GoogleTVPerso/" + BuildConfig.VERSION_CODE);
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                    if (conn.getResponseCode() != 200) { dispatchUpdateResult("null"); return; }
                    java.util.Scanner sc = new java.util.Scanner(conn.getInputStream(), "UTF-8")
                        .useDelimiter("\\A");
                    String json = sc.hasNext() ? sc.next() : "";
                    JSONObject obj = new JSONObject(json);
                    String tagName = obj.getString("tag_name"); // "v1.0.5"
                    String[] parts = tagName.replace("v", "").split("\\.");
                    int latest = Integer.parseInt(parts[parts.length - 1]);
                    String dlUrl = "";
                    JSONArray assets = obj.optJSONArray("assets");
                    if (assets != null && assets.length() > 0) {
                        dlUrl = assets.getJSONObject(0).getString("browser_download_url");
                    }
                    JSONObject result = new JSONObject();
                    result.put("currentVersion", BuildConfig.VERSION_CODE);
                    result.put("latestVersion", latest);
                    result.put("tagName", tagName);
                    result.put("downloadUrl", dlUrl);
                    result.put("upToDate", latest <= BuildConfig.VERSION_CODE);
                    dispatchUpdateResult(result.toString());
                } catch (Exception e) {
                    dispatchUpdateResult("null");
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }).start();
        }

        /** Télécharge l'APK depuis url et déclenche l'installation système. */
        @JavascriptInterface
        public void downloadAndInstall(final String apkUrl) {
            new Thread(() -> {
                java.net.HttpURLConnection conn = null;
                java.io.FileOutputStream fos = null;
                try {
                    java.io.File dir = new java.io.File(act.getCacheDir(), "apk");
                    dir.mkdirs();
                    java.io.File apkFile = new java.io.File(dir, "GoogleTVPerso-update.apk");

                    java.net.URL u = new java.net.URL(apkUrl);
                    conn = (java.net.HttpURLConnection) u.openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(120000);
                    conn.connect();
                    int total = conn.getContentLength();
                    java.io.InputStream is = conn.getInputStream();
                    fos = new java.io.FileOutputStream(apkFile);
                    byte[] buf = new byte[8192];
                    int read, downloaded = 0, lastPct = -1;
                    while ((read = is.read(buf)) != -1) {
                        fos.write(buf, 0, read);
                        downloaded += read;
                        if (total > 0) {
                            int pct = (downloaded * 100) / total;
                            if (pct / 10 != lastPct / 10) {
                                lastPct = pct;
                                final int p = pct;
                                act.runOnUiThread(() ->
                                    act.webView.evaluateJavascript(
                                        "window._onDownloadProgress(" + p + ");", null)
                                );
                            }
                        }
                    }
                    fos.close(); fos = null;
                    is.close();

                    final java.io.File apk = apkFile;
                    act.runOnUiThread(() -> {
                        try {
                            Uri uri = FileProvider.getUriForFile(
                                act, act.getPackageName() + ".fileprovider", apk);
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setDataAndType(uri, "application/vnd.android.package-archive");
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                          | Intent.FLAG_ACTIVITY_NEW_TASK);
                            act.startActivity(intent);
                            act.webView.evaluateJavascript(
                                "window.dispatchEvent(new CustomEvent('update_install_started'));", null);
                        } catch (Exception e) {
                            Toast.makeText(act, "Erreur installation: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception e) {
                    act.runOnUiThread(() ->
                        Toast.makeText(act, "Erreur téléchargement: " + e.getMessage(),
                            Toast.LENGTH_LONG).show()
                    );
                } finally {
                    if (conn != null) conn.disconnect();
                    if (fos != null) { try { fos.close(); } catch (Exception ignored) {} }
                }
            }).start();
        }

        private void dispatchUpdateResult(final String json) {
            act.runOnUiThread(() ->
                act.webView.evaluateJavascript("window._onUpdateResult(" + json + ");", null)
            );
        }
    }
}
