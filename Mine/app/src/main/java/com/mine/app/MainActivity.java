package com.mine.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.CancellationSignal;
import android.hardware.biometrics.BiometricPrompt;
import android.view.WindowManager;
import android.webkit.WebResourceResponse;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingAction = null;
    private String pendingJs = null;
    private boolean loaded = false;
    private static final int FILE_REQ = 42;
    private static final int LOCK_REQ = 43;
    private static final long RELOCK_MS = 30_000L;
    private long leftAt = 0;
    private boolean authing = false;
    private boolean unlocked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Hide contents from screenshots and the recent-apps preview
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0A0F0C"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setGeolocationEnabled(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);

        web.addJavascriptInterface(new Bridge(this), "MineNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (isAppPage(u)) return false;
                String sc = u.getScheme();
                if ("https".equals(sc) || "mailto".equals(sc) || "tel".equals(sc) || "upi".equals(sc)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ignored) { }
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (isAppPage(u)) return null;
                // The app is fully offline: block every other load inside the WebView
                return new WebResourceResponse("text/plain", "utf-8", null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loaded = true;
                flushPending();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent i = params.createIntent();
                    i.setType("*/*");
                    startActivityForResult(i, FILE_REQ);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }

        handleIntent(getIntent());
        if (lockEnabled()) { web.setVisibility(View.INVISIBLE); }
        web.loadUrl("file:///android_asset/index.html");
    }

    private static boolean isAppPage(Uri u) {
        return "file".equals(u.getScheme()) && u.getPath() != null && u.getPath().startsWith("/android_asset/");
    }

    SharedPreferences prefs() {
        return getSharedPreferences("mine_security", Context.MODE_PRIVATE);
    }

    boolean lockEnabled() {
        return prefs().getBoolean("lock", false) && deviceSecure();
    }

    boolean deviceSecure() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    void setLockEnabled(boolean on) {
        prefs().edit().putBoolean("lock", on && deviceSecure()).apply();
    }

    private void requireUnlock() {
        if (!lockEnabled()) { web.setVisibility(View.VISIBLE); return; }
        if (unlocked && (leftAt == 0 || System.currentTimeMillis() - leftAt < RELOCK_MS)) { web.setVisibility(View.VISIBLE); return; }
        unlocked = false;
        web.setVisibility(View.INVISIBLE);
        if (authing) return;
        authing = true;
        if (Build.VERSION.SDK_INT >= 30 && showBiometricPrompt()) return;
        // Android 8-10, or the prompt failed: use the phone's PIN / pattern screen
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        Intent i = km.createConfirmDeviceCredentialIntent("Unlock Mine", null);
        if (i == null) { onUnlocked(); return; }
        startActivityForResult(i, LOCK_REQ);
    }

    private boolean showBiometricPrompt() {
        try {
            new BiometricPrompt.Builder(this)
                    .setTitle("Unlock Mine")
                    .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                            | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()
                    .authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                            onUnlocked();
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence msg) {
                            authing = false;
                            finish();
                        }
                    });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!authing && unlocked) leftAt = System.currentTimeMillis();
    }

    private void onUnlocked() {
        authing = false;
        unlocked = true;
        leftAt = 0;
        web.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && ExpenseWidget.ACTION_ADD_EXPENSE.equals(intent.getAction())) {
            pendingJs = "window.nativeAddExpense&&window.nativeAddExpense()";
            intent.setAction(Intent.ACTION_MAIN);
            flushPending();
            return;
        }
        if (intent == null || !AlarmReceiver.ACTION_DONE.equals(intent.getAction())) return;
        String json = intent.getStringExtra("json");
        if (json == null) return;
        try {
            String key = new JSONObject(json).getString("key");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.cancel(key.hashCode());
        } catch (Exception ignored) { }
        pendingAction = json;
        intent.setAction(Intent.ACTION_MAIN);
        flushPending();
    }

    private void flushPending() {
        if (loaded && pendingJs != null) {
            String js = pendingJs;
            pendingJs = null;
            web.evaluateJavascript(js, null);
        }
        if (loaded && pendingAction != null) {
            String j = pendingAction;
            pendingAction = null;
            web.evaluateJavascript("window.nativeAlarmAction&&window.nativeAlarmAction(" + j + ")", null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LOCK_REQ) {
            authing = false;
            if (resultCode == RESULT_OK) onUnlocked();
            else finish();
            return;
        }
        if (requestCode == FILE_REQ && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.mineBack?window.mineBack():false", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        requireUnlock();
        if (loaded) web.evaluateJavascript("window.mineResume&&window.mineResume()", null);
    }
}
