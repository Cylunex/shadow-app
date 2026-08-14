package com.shadow.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.shadow.app.core.AppModule;
import com.shadow.app.core.ModuleRegistry;
import com.shadow.app.core.ServerConfig;
import com.shadow.app.core.UrlTools;
import com.shadow.app.health.HealthFeature;
import com.shadow.app.health.HealthOffline;
import com.shadow.app.health.Reminders;
import com.shadow.app.health.SamsungSync;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Generic multi-web-app container. Feature-specific code lives outside this activity. */
public class MainActivity extends Activity {
    private static final String HOME_URL = "file:///android_asset/home.html";
    private static final String ERROR_URL = "file:///android_asset/error.html";
    private static final String HEALTH_OFFLINE_URL = "file:///android_asset/health-offline.html";
    private static final long HEALTH_PROBE_INTERVAL_MS = 30_000;
    private static final int FILE_CHOOSER_REQUEST = 44;
    private static final int BACKGROUND = Color.rgb(7, 17, 31);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable healthProbe = this::probeHealth;
    private WebView webView;
    private TextView titleView;
    private SharedPreferences prefs;
    private ModuleRegistry registry;
    private HealthFeature healthFeature;
    private ValueCallback<Uri[]> fileCallback;
    private volatile String currentModuleId;
    private volatile String currentPageUrl = HOME_URL;
    private volatile String lastError = "";
    private String erroredUrl;
    private boolean loadingErrorPage;
    private boolean showingHealthOffline;
    private boolean showingHealthSnapshot;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(BACKGROUND);
        window.setNavigationBarColor(BACKGROUND);

        prefs = getSharedPreferences(ServerConfig.PREFS_NAME, MODE_PRIVATE);
        registry = ModuleRegistry.load(this);
        healthFeature = new HealthFeature(this);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BACKGROUND);
        screen.addView(createToolbar());

        webView = new WebView(this);
        webView.setBackgroundColor(BACKGROUND);
        screen.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(screen);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new ShellBridge(), "ShellBridge");
        webView.setWebViewClient(createWebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                    return true;
                } catch (RuntimeException e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this,
                            "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });
        webView.setDownloadListener(this::download);

        openHome();
        if (ServerConfig.urls(this).isEmpty()) {
            mainHandler.post(() -> showSettings(true));
        }
        healthFeature.restore();
        if (HealthOffline.queueSize(this) > 0) {
            HealthOffline.scheduleFlush(this);
        }
    }

    private View createToolbar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(6), 0, dp(6), 0);
        toolbar.setBackgroundColor(Color.rgb(15, 23, 42));
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        toolbar.addView(toolbarButton("‹", view -> navigateBack()));
        toolbar.addView(toolbarButton("⌂", view -> openHome()));
        titleView = new TextView(this);
        titleView.setText("Shadow");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(16);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setPadding(dp(10), 0, dp(6), 0);
        toolbar.addView(titleView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        toolbar.addView(toolbarButton("↻", view -> webView.reload()));
        toolbar.addView(toolbarButton("⋮", view -> showSettings(false)));
        return toolbar;
    }

    private Button toolbarButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(20);
        button.setTextColor(Color.rgb(203, 213, 225));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setMinWidth(dp(44));
        button.setMinimumWidth(dp(44));
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return button;
    }

    private WebViewClient createWebViewClient() {
        return new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                currentPageUrl = url;
                if (url != null && url.startsWith("http")) {
                    updateCurrentModule(url);
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                return HealthOffline.intercept(getApplicationContext(), request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("file:///android_asset/")) {
                    return false;
                }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    if (ServerConfig.isTrustedModuleUrl(MainActivity.this, url)) {
                        return false;
                    }
                    return openExternal(url);
                }
                return openExternal(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                currentPageUrl = url;
                if (url != null && url.equals(erroredUrl)) {
                    erroredUrl = null;
                    return;
                }
                if (HOME_URL.equals(url)) {
                    currentModuleId = null;
                    titleView.setText("Shadow");
                } else if (url != null && url.startsWith("http")) {
                    updateCurrentModule(url);
                    if ("health".equals(currentModuleId)) {
                        showingHealthOffline = false;
                        if (HealthOffline.consumeReplayedMain(url)) {
                            showingHealthSnapshot = true;
                            scheduleHealthProbe();
                        } else {
                            showingHealthSnapshot = false;
                            mainHandler.removeCallbacks(healthProbe);
                            maybeRefreshHealthBootstrap();
                            if (HealthOffline.queueSize(MainActivity.this) > 0) {
                                HealthOffline.scheduleFlush(MainActivity.this);
                            }
                        }
                    }
                }
                if (!ERROR_URL.equals(url)) {
                    loadingErrorPage = false;
                }
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view,
                                                  android.webkit.HttpAuthHandler handler,
                                                  String host, String realm) {
                String[] credentials = ServerConfig.credentialsForHost(MainActivity.this, host);
                if (credentials == null) {
                    handler.cancel();
                } else {
                    handler.proceed(credentials[0], credentials[1]);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (!request.isForMainFrame() || loadingErrorPage
                        || request.getUrl().toString().startsWith("file://")) {
                    return;
                }
                lastError = "无法打开 " + request.getUrl() + "\n" + error.getDescription();
                erroredUrl = request.getUrl().toString();
                if ("health".equals(moduleIdForUrl(request.getUrl().toString()))) {
                    showHealthOffline();
                    return;
                }
                String failedModule = moduleIdForUrl(request.getUrl().toString());
                String failedBase = ServerConfig.active(MainActivity.this);
                loadingErrorPage = true;
                showingHealthOffline = false;
                showingHealthSnapshot = false;
                mainHandler.removeCallbacks(healthProbe);
                view.loadUrl(ERROR_URL);
                probeAlternative(failedModule, failedBase);
            }
        };
    }

    private boolean openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Toast.makeText(this, "没有可打开此链接的应用", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void openHome() {
        currentModuleId = null;
        currentPageUrl = HOME_URL;
        lastError = "";
        loadingErrorPage = false;
        erroredUrl = null;
        showingHealthOffline = false;
        showingHealthSnapshot = false;
        mainHandler.removeCallbacks(healthProbe);
        titleView.setText("Shadow");
        webView.loadUrl(HOME_URL);
        webView.clearHistory();
    }

    private void openModule(String moduleId) {
        AppModule module = registry.get(moduleId);
        if (module == null || !module.enabled) {
            Toast.makeText(this, "应用不存在或未启用", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ServerConfig.active(this).isEmpty()) {
            showSettings(true);
            return;
        }
        currentModuleId = module.id;
        lastError = "";
        loadingErrorPage = false;
        erroredUrl = null;
        showingHealthOffline = false;
        showingHealthSnapshot = false;
        mainHandler.removeCallbacks(healthProbe);
        titleView.setText(module.name);
        currentPageUrl = UrlTools.bare(ServerConfig.moduleUrl(this, registry, module.id));
        webView.loadUrl(currentPageUrl);
    }

    private void updateCurrentModule(String url) {
        String moduleId = moduleIdForUrl(url);
        currentModuleId = moduleId;
        AppModule module = moduleId == null ? null : registry.get(moduleId);
        titleView.setText(module == null ? "Shadow" : module.name);
    }

    private String moduleIdForUrl(String url) {
        for (AppModule module : registry.all()) {
            String moduleUrl = UrlTools.bare(ServerConfig.moduleUrl(this, registry, module.id));
            if (!moduleUrl.isEmpty() && (url.equals(moduleUrl) || url.startsWith(moduleUrl))) {
                return module.id;
            }
        }
        return null;
    }

    private void showHealthOffline() {
        currentModuleId = "health";
        currentPageUrl = HEALTH_OFFLINE_URL;
        titleView.setText("健康 · 离线");
        loadingErrorPage = false;
        showingHealthOffline = true;
        showingHealthSnapshot = false;
        webView.loadUrl(HEALTH_OFFLINE_URL);
        scheduleHealthProbe();
    }

    private void scheduleHealthProbe() {
        mainHandler.removeCallbacks(healthProbe);
        mainHandler.postDelayed(healthProbe, HEALTH_PROBE_INTERVAL_MS);
    }

    private void probeHealth() {
        mainHandler.removeCallbacks(healthProbe);
        io.execute(() -> {
            String available = HealthOffline.availableEndpoint(MainActivity.this);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!available.isEmpty() && (showingHealthOffline || showingHealthSnapshot)) {
                    openModule("health");
                } else if (showingHealthOffline || showingHealthSnapshot) {
                    if (showingHealthOffline) {
                        webView.evaluateJavascript(
                                "window.onProbeResult&&window.onProbeResult(false)", null);
                    }
                    scheduleHealthProbe();
                }
            });
        });
    }

    private void maybeRefreshHealthBootstrap() {
        if (HealthOffline.bootstrapAgeMs(this) >= HealthOffline.BOOTSTRAP_REFRESH_MS) {
            io.execute(() -> HealthOffline.fetchBootstrap(getApplicationContext()));
        }
    }

    private void probeAlternative(String moduleId, String failedBase) {
        if (moduleId == null) {
            return;
        }
        io.execute(() -> {
            String alternative = ServerConfig.availableAlternativeModule(
                    MainActivity.this, registry, moduleId, failedBase);
            mainHandler.post(() -> {
                if (!alternative.isEmpty() && loadingErrorPage
                        && moduleId.equals(currentModuleId)
                        && !isFinishing() && !isDestroyed()) {
                    ServerConfig.activate(MainActivity.this, alternative);
                    openModule(moduleId);
                }
            });
        });
    }

    private void navigateBack() {
        if (webView.canGoBack() && currentModuleId != null) {
            webView.goBack();
        } else {
            openHome();
        }
    }

    private boolean isHealthBridgeAllowed() {
        if (!"health".equals(currentModuleId)) {
            return false;
        }
        if (HEALTH_OFFLINE_URL.equals(currentPageUrl)) {
            return true;
        }
        String healthUrl = UrlTools.bare(
                ServerConfig.moduleUrl(this, registry, "health"));
        return currentPageUrl != null && !healthUrl.isEmpty()
                && (currentPageUrl.equals(healthUrl) || currentPageUrl.startsWith(healthUrl));
    }

    private void showSettings(boolean firstRun) {
        EditText servers = new EditText(this);
        servers.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        servers.setMinLines(2);
        servers.setMaxLines(4);
        servers.setHint("门户地址，每行一个，内网地址优先");
        List<String> configured = ServerConfig.urls(this);
        servers.setText(configured.isEmpty()
                ? ServerConfig.DEFAULT_SERVER_URL : String.join("\n", configured));

        EditText token = new EditText(this);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setHint("健康服务 INGEST_TOKEN");
        token.setText(prefs.getString(HealthFeature.KEY_INGEST_TOKEN, ""));

        CheckBox scale = new CheckBox(this);
        scale.setText("后台监听体脂秤");
        scale.setChecked(prefs.getBoolean(HealthFeature.KEY_SCALE_SCAN, false));

        EditText bindkey = new EditText(this);
        bindkey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        bindkey.setHint("小米 S400 bindkey（旧秤留空）");
        bindkey.setText(prefs.getString(HealthFeature.KEY_SCALE_BINDKEY, ""));

        CheckBox samsung = new CheckBox(this);
        samsung.setText(SamsungSync.isAvailable()
                ? "同步三星健康数据" : "同步三星健康数据（未安装 SDK）");
        samsung.setEnabled(SamsungSync.isAvailable());
        samsung.setChecked(SamsungSync.isAvailable()
                && prefs.getBoolean(SamsungSync.PREF_ENABLED, false));

        CheckBox reminder = new CheckBox(this);
        reminder.setText("每日健康提醒（20:30）");
        reminder.setChecked(prefs.getBoolean(Reminders.PREF_ENABLED, false));

        TextView hint = new TextView(this);
        hint.setText("模块路径由 modules.json 统一管理；当前健康为 /shealth/，股票为 /stock/。"
                + "地址可包含 HTTP Basic 用户名和密码。");
        hint.setTextSize(12);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(6), dp(18), 0);
        content.addView(servers);
        content.addView(token);
        content.addView(scale);
        content.addView(bindkey);
        content.addView(samsung);
        content.addView(reminder);
        content.addView(hint);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Shadow 连接设置")
                .setView(content)
                .setPositiveButton("保存", (dialog, which) -> {
                    List<String> values = new ArrayList<>();
                    for (String line : servers.getText().toString().split("\n")) {
                        String value = UrlTools.normalizeBase(line);
                        if (!value.isEmpty() && !values.contains(value)) {
                            values.add(value);
                        }
                    }
                    if (values.isEmpty()) {
                        values.add(ServerConfig.DEFAULT_SERVER_URL);
                    }
                    ServerConfig.save(this, values);
                    healthFeature.applySettings(
                            token.getText().toString(), scale.isChecked(),
                            bindkey.getText().toString(), samsung.isChecked(), reminder.isChecked());
                    if (currentModuleId == null) {
                        webView.reload();
                    } else {
                        openModule(currentModuleId);
                    }
                });
        if (firstRun) {
            builder.setCancelable(false);
        } else {
            builder.setNegativeButton("取消", null);
        }
        builder.show();
    }

    private void download(String url, String userAgent, String contentDisposition,
                          String mimeType, long contentLength) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) {
                request.addRequestHeader("Cookie", cookie);
            }
            String basic = ServerConfig.basicAuthHeaderForUrl(this, url);
            if (basic != null) {
                request.addRequestHeader("Authorization", basic);
            }
            String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            manager.enqueue(request);
            Toast.makeText(this, "已开始下载 " + filename, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        healthFeature.onRequestPermissionsResult(requestCode, permissions, results);
    }

    @Override
    public void onBackPressed() {
        if (currentModuleId != null) {
            navigateBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        if (HealthOffline.queueSize(this) > 0) {
            HealthOffline.scheduleFlush(this);
        }
        if (showingHealthOffline || showingHealthSnapshot) {
            mainHandler.post(healthProbe);
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(healthProbe);
        io.shutdownNow();
        CookieManager.getInstance().flush();
        webView.removeJavascriptInterface("ShellBridge");
        webView.destroy();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class ShellBridge {
        @JavascriptInterface
        public String getModules() {
            return registry.clientJson(ServerConfig.active(MainActivity.this));
        }

        @JavascriptInterface
        public void openModule(String moduleId) {
            mainHandler.post(() -> MainActivity.this.openModule(moduleId));
        }

        @JavascriptInterface
        public void openHome() {
            mainHandler.post(MainActivity.this::openHome);
        }

        @JavascriptInterface
        public void openSettings() {
            mainHandler.post(() -> showSettings(false));
        }

        /** Legacy alias used by the health offline page. */
        @JavascriptInterface
        public void changeAddress() {
            openSettings();
        }

        @JavascriptInterface
        public void openOfflinePage() {
            if (isHealthBridgeAllowed()) {
                mainHandler.post(MainActivity.this::showHealthOffline);
            }
        }

        @JavascriptInterface
        public void probeNow() {
            if (isHealthBridgeAllowed()) {
                mainHandler.post(healthProbe);
            }
        }

        @JavascriptInterface
        public String getBootstrap() {
            return isHealthBridgeAllowed()
                    ? HealthOffline.bootstrap(MainActivity.this) : "";
        }

        @JavascriptInterface
        public String getOfflineStatus() {
            return isHealthBridgeAllowed()
                    ? HealthOffline.status(MainActivity.this, lastError) : "{}";
        }

        @JavascriptInterface
        public int enqueueRecord(String type, String payloadJson) {
            return isHealthBridgeAllowed()
                    ? HealthOffline.enqueue(MainActivity.this, type, payloadJson) : -1;
        }

        @JavascriptInterface
        public int setQueuedHabit(int habitId, int doneCount) {
            return isHealthBridgeAllowed()
                    ? HealthOffline.setQueuedHabit(MainActivity.this, habitId, doneCount) : -1;
        }

        @JavascriptInterface
        public String getQueuedHabits() {
            return isHealthBridgeAllowed()
                    ? HealthOffline.queuedHabits(MainActivity.this) : "{}";
        }

        @JavascriptInterface
        public void retry() {
            mainHandler.post(() -> {
                if (currentModuleId == null) {
                    openHome();
                } else {
                    openModule(currentModuleId);
                }
            });
        }

        @JavascriptInterface
        public String getError() {
            try {
                return new JSONObject().put("message", lastError).toString();
            } catch (JSONException ignored) {
                return "{}";
            }
        }

        /** Compatibility contract used by the existing shadow-health pages. */
        @JavascriptInterface
        public void startScaleScan() {
            if (!isHealthBridgeAllowed()) {
                return;
            }
            mainHandler.post(healthFeature::startTimedScaleScan);
        }
    }
}
