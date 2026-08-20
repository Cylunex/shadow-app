package com.shadow.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
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
import android.widget.ImageView;
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Generic multi-web-app container. Feature-specific code lives outside this activity. */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final String HOME_URL = "file:///android_asset/home.html";
    private static final String ERROR_URL = "file:///android_asset/error.html";
    private static final String HEALTH_OFFLINE_URL = "file:///android_asset/health-offline.html";
    private static final long HEALTH_PROBE_INTERVAL_MS = 30_000;
    private static final int FILE_CHOOSER_REQUEST = 44;
    private static final int BACKGROUND = Color.rgb(13, 17, 23);
    private static final int BRAND_CYAN = Color.rgb(166, 241, 255);

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
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            // Android 10 still lets the framework place content below opaque system bars.
            window.setStatusBarColor(BACKGROUND);
            window.setNavigationBarColor(BACKGROUND);
        }

        prefs = getSharedPreferences(ServerConfig.PREFS_NAME, MODE_PRIVATE);
        ServerConfig.discardLegacyOverrides(this);
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
        applySystemBarAppearance(window);
        applySystemBarInsets(screen);

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
        // Native health integrations are optional. Restore them after the shell is visible and
        // never let a vendor-specific failure take down the web container during app startup.
        mainHandler.post(() -> {
            try {
                healthFeature.restore();
                if (HealthOffline.queueSize(this) > 0) {
                    HealthOffline.scheduleFlush(this);
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "restore health integrations failed", e);
            }
        });
    }

    private View createToolbar() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(6), 0, dp(6), 0);
        toolbar.setBackgroundColor(BACKGROUND);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(47)));

        toolbar.addView(toolbarButton("‹", view -> navigateBack()));
        ImageView brandIcon = new ImageView(this);
        brandIcon.setImageResource(R.mipmap.ic_launcher);
        brandIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        brandIcon.setContentDescription("返回 Shadow 应用中心");
        brandIcon.setOnClickListener(view -> openHome());
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        iconParams.setMargins(dp(2), 0, dp(6), 0);
        toolbar.addView(brandIcon, iconParams);
        titleView = new TextView(this);
        titleView.setText("应用中心");
        titleView.setTextColor(Color.rgb(238, 244, 246));
        titleView.setTextSize(15);
        titleView.setTypeface(Typeface.create("serif", Typeface.BOLD));
        titleView.setLetterSpacing(0.06f);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setPadding(dp(4), 0, dp(6), 0);
        toolbar.addView(titleView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        toolbar.addView(toolbarButton("↻", view -> webView.reload()));
        toolbar.addView(toolbarButton("⋮", view -> showSettings()));
        shell.addView(toolbar);
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(34, 58, 70));
        shell.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return shell;
    }

    private void applySystemBarInsets(View root) {
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                int types = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
                Insets bars = windowInsets.getInsets(types);
                setPaddingIfChanged(view, bars.left, bars.top, bars.right, bars.bottom);
                // Preserve the platform object. Rebuilding/consuming it can trigger repeated
                // layout dispatches on some Samsung One UI versions.
                return windowInsets;
            }
            setPaddingIfChanged(view,
                    windowInsets.getSystemWindowInsetLeft(),
                    windowInsets.getSystemWindowInsetTop(),
                    windowInsets.getSystemWindowInsetRight(),
                    windowInsets.getSystemWindowInsetBottom());
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    private void applySystemBarAppearance(Window window) {
        if (Build.VERSION.SDK_INT < 30) {
            return;
        }
        // Android 16's PhoneWindow can throw before setContentView() creates the DecorView.
        WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
        if (controller != null) {
            controller.setSystemBarsAppearance(0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    private void setPaddingIfChanged(View view, int left, int top, int right, int bottom) {
        if (view.getPaddingLeft() != left || view.getPaddingTop() != top
                || view.getPaddingRight() != right || view.getPaddingBottom() != bottom) {
            view.setPadding(left, top, right, bottom);
        }
    }

    private Button toolbarButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(20);
        button.setTextColor(BRAND_CYAN);
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
                    if (ServerConfig.isTrustedNavigationUrl(registry, url)) {
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
                    titleView.setText("应用中心");
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
                // Platform URLs never embed credentials; browser and WebView auth use app sessions.
                handler.cancel();
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
                String failedBase = request.getUrl().toString();
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
        titleView.setText("应用中心");
        webView.loadUrl(HOME_URL);
        webView.clearHistory();
    }

    private void openModule(String moduleId) {
        AppModule module = registry.get(moduleId);
        if (module == null || !module.enabled) {
            Toast.makeText(this, "应用不存在或未启用", Toast.LENGTH_SHORT).show();
            return;
        }
        String targetUrl = ServerConfig.moduleUrl(this, registry, module.id);
        if (targetUrl.isEmpty()) {
            Toast.makeText(this, "Platform 未提供此应用入口", Toast.LENGTH_SHORT).show();
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
        currentPageUrl = UrlTools.bare(targetUrl);
        webView.loadUrl(currentPageUrl);
    }

    private void updateCurrentModule(String url) {
        if (registry.isIdentityUrl(url)) {
            AppModule current = currentModuleId == null ? null : registry.get(currentModuleId);
            titleView.setText(current == null ? "Shadow 登录" : current.name + " · 登录");
            return;
        }
        String moduleId = moduleIdForUrl(url);
        currentModuleId = moduleId;
        AppModule module = moduleId == null ? null : registry.get(moduleId);
        titleView.setText(module == null ? "Shadow" : module.name);
    }

    private String moduleIdForUrl(String url) {
        String matchedModuleId = null;
        int matchedLength = -1;
        for (AppModule module : registry.all()) {
            for (String candidate : ServerConfig.moduleUrls(registry, module.id)) {
                String moduleUrl = UrlTools.bare(candidate);
                if (!moduleUrl.isEmpty()
                        && UrlTools.isWithinBase(url, moduleUrl)
                        && moduleUrl.length() > matchedLength) {
                    matchedModuleId = module.id;
                    matchedLength = moduleUrl.length();
                }
            }
        }
        return matchedModuleId;
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
        if (currentPageUrl == null) {
            return false;
        }
        for (String candidate : ServerConfig.moduleUrls(registry, "health")) {
            String healthUrl = UrlTools.bare(candidate);
            if (!healthUrl.isEmpty()
                    && (currentPageUrl.equals(healthUrl) || currentPageUrl.startsWith(healthUrl))) {
                return true;
            }
        }
        return false;
    }

    private void showSettings() {
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
        hint.setText("应用地址和认证方式由 Shadow Platform Catalog 管理，无需手动填写。\n"
                + "统一登录：" + registry.identityIssuer() + "\n"
                + "壳会在应用规范域名与本地 DNS 别名之间自动切换；这里只保留健康设备设置。");
        hint.setTextSize(12);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(6), dp(18), 0);
        content.addView(token);
        content.addView(scale);
        content.addView(bindkey);
        content.addView(samsung);
        content.addView(reminder);
        content.addView(hint);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Shadow 平台与设备")
                .setView(content)
                .setPositiveButton("保存", (dialog, which) -> {
                    healthFeature.applySettings(
                            token.getText().toString(), scale.isChecked(),
                            bindkey.getText().toString(), samsung.isChecked(), reminder.isChecked());
                })
                .setNegativeButton("取消", null);
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
            return registry.clientJson(MainActivity.this);
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
            mainHandler.post(MainActivity.this::showSettings);
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
