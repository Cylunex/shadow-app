package com.shadow.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.shadow.app.core.AppModule;
import com.shadow.app.core.ModuleRegistry;
import com.shadow.app.core.NativeBridgeSession;
import com.shadow.app.core.NavigationPolicy;
import com.shadow.app.core.ServerConfig;
import com.shadow.app.core.UrlTools;
import com.shadow.app.health.HealthFeature;
import com.shadow.app.health.HealthOffline;
import com.shadow.app.health.Reminders;
import com.shadow.app.health.SamsungSync;
import com.shadow.app.nexus.NexusNative;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

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
    private static final int SURFACE = Color.rgb(11, 18, 26);
    private static final int NAV_MUTED = Color.rgb(112, 133, 148);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable healthProbe = this::probeHealth;
    private final NativeBridgeSession nativeBridgeSession = new NativeBridgeSession();
    private final SecureRandom secureRandom = new SecureRandom();
    private final ShellBridge localShellBridge = new ShellBridge();
    private WebView webView;
    private View shellToolbar;
    private TextView titleView;
    private final Map<String, NavItemRefs> navigationItems = new HashMap<>();
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
    private volatile PendingShareCapture pendingShareCapture;
    private volatile boolean localBridgeAttached;

    private static final class NavItemRefs {
        final ImageView icon;
        final TextView label;

        NavItemRefs(ImageView icon, TextView label) {
            this.icon = icon;
            this.label = label;
        }
    }

    private static final class PendingShareCapture {
        final String id;
        final String sourceType;
        final String text;
        final Uri stream;
        final String filename;
        final String mimeType;
        final long sizeBytes;
        final String sourceApp;

        PendingShareCapture(String id, String sourceType, String text, Uri stream,
                            String filename, String mimeType, long sizeBytes,
                            String sourceApp) {
            this.id = id;
            this.sourceType = sourceType;
            this.text = text;
            this.stream = stream;
            this.filename = filename;
            this.mimeType = mimeType;
            this.sizeBytes = sizeBytes;
            this.sourceApp = sourceApp;
        }
    }

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

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BACKGROUND);
        shellToolbar = createToolbar();
        screen.addView(shellToolbar);

        webView = new WebView(this);
        webView.setBackgroundColor(BACKGROUND);
        screen.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        FrameLayout.LayoutParams screenParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        screenParams.bottomMargin = dp(80);
        root.addView(screen, screenParams);

        View bottomNavigation = createBottomNavigation();
        FrameLayout.LayoutParams navigationParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(88), Gravity.BOTTOM);
        root.addView(bottomNavigation, navigationParams);

        setContentView(root);
        applySystemBarAppearance(window);
        applySystemBarInsets(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setSafeBrowsingEnabled(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);

        setLocalBridgeAttached(true);
        installNativeMessageBridge();
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
        acceptShareIntent(getIntent());
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
            try {
                NexusNative.restore(getApplicationContext());
            } catch (RuntimeException e) {
                Log.e(TAG, "restore Nexus native queue failed", e);
            }
        });
    }

    private View createToolbar() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), 0, dp(8), 0);
        toolbar.setBackgroundColor(SURFACE);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(55)));

        toolbar.addView(toolbarIcon(R.drawable.ic_toolbar_back, "返回", view -> navigateBack()));
        titleView = new TextView(this);
        titleView.setText("应用中心");
        titleView.setTextColor(Color.rgb(238, 244, 246));
        titleView.setTextSize(17);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setPadding(dp(8), 0, dp(8), 0);
        toolbar.addView(titleView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        toolbar.addView(toolbarIcon(R.drawable.ic_toolbar_refresh, "刷新", view -> webView.reload()));
        toolbar.addView(toolbarIcon(R.drawable.ic_toolbar_more, "设置", view -> showSettings()));
        shell.addView(toolbar);
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(28, 43, 54));
        shell.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return shell;
    }

    private View createBottomNavigation() {
        FrameLayout dock = new FrameLayout(this);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setElevation(dp(16));

        GradientDrawable dockBackground = new GradientDrawable();
        dockBackground.setColor(SURFACE);
        dockBackground.setCornerRadii(new float[]{
                dp(22), dp(22), dp(22), dp(22), 0, 0, 0, 0});

        LinearLayout destinations = new LinearLayout(this);
        destinations.setGravity(Gravity.CENTER_VERTICAL);
        destinations.setPadding(dp(4), dp(4), dp(4), 0);
        destinations.setBackground(dockBackground);
        FrameLayout.LayoutParams destinationParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(72), Gravity.BOTTOM);
        dock.addView(destinations, destinationParams);

        destinations.addView(createNavigationItem("home", "首页", R.drawable.ic_nav_home,
                view -> openHome()), weightedNavigationParams());
        destinations.addView(createNavigationItem("apps", "应用", R.drawable.ic_nav_apps,
                view -> openAppCenter()), weightedNavigationParams());
        Space actionGap = new Space(this);
        destinations.addView(actionGap, new LinearLayout.LayoutParams(dp(72), dp(1)));
        destinations.addView(createNavigationItem("health", "健康", R.drawable.ic_nav_health,
                view -> openModule("health")), weightedNavigationParams());
        destinations.addView(createNavigationItem("settings", "设置", R.drawable.ic_nav_settings,
                view -> showSettings()), weightedNavigationParams());

        LinearLayout scaleAction = new LinearLayout(this);
        scaleAction.setOrientation(LinearLayout.VERTICAL);
        scaleAction.setGravity(Gravity.CENTER_HORIZONTAL);
        scaleAction.setContentDescription("接收体重，开启体脂秤监听三分钟");

        ImageButton scaleButton = new ImageButton(this);
        scaleButton.setImageResource(R.drawable.ic_nav_scale);
        scaleButton.setColorFilter(Color.rgb(5, 19, 24), PorterDuff.Mode.SRC_IN);
        scaleButton.setScaleType(ImageView.ScaleType.CENTER);
        scaleButton.setPadding(dp(15), dp(15), dp(15), dp(15));
        GradientDrawable scaleShape = new GradientDrawable();
        scaleShape.setShape(GradientDrawable.OVAL);
        scaleShape.setColor(BRAND_CYAN);
        scaleShape.setStroke(dp(1), Color.rgb(74, 184, 199));
        scaleButton.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(45, 0, 0, 0)), scaleShape, null));
        scaleButton.setElevation(dp(10));
        scaleButton.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            healthFeature.startTimedScaleScan();
        });
        scaleAction.addView(scaleButton, new LinearLayout.LayoutParams(dp(58), dp(58)));

        TextView scaleLabel = new TextView(this);
        scaleLabel.setText("称重");
        scaleLabel.setTextColor(BRAND_CYAN);
        scaleLabel.setTextSize(11);
        scaleLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        scaleLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams scaleLabelParams = new LinearLayout.LayoutParams(dp(72), dp(24));
        scaleLabelParams.topMargin = dp(1);
        scaleAction.addView(scaleLabel, scaleLabelParams);

        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(
                dp(80), dp(88), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        dock.addView(scaleAction, actionParams);
        return dock;
    }

    private LinearLayout.LayoutParams weightedNavigationParams() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
    }

    private View createNavigationItem(String key, String labelText, int iconResource,
                                      View.OnClickListener listener) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setMinimumHeight(dp(56));
        item.setContentDescription(labelText);
        item.setOnClickListener(listener);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setPadding(dp(10), dp(5), dp(10), dp(5));
        item.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(34)));

        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextSize(11);
        label.setGravity(Gravity.CENTER);
        item.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(22)));
        navigationItems.put(key, new NavItemRefs(icon, label));
        return item;
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

    private ImageButton toolbarIcon(int iconResource, String description,
                                    View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResource);
        button.setColorFilter(Color.rgb(179, 199, 209), PorterDuff.Mode.SRC_IN);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(description);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    private WebViewClient createWebViewClient() {
        return new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                nativeBridgeSession.invalidate();
                currentPageUrl = url;
                if (url != null && url.startsWith("http")) {
                    updateCurrentModule(url);
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                WebResourceResponse shared = interceptSharedCapture(request);
                if (shared != null) {
                    return shared;
                }
                return HealthOffline.intercept(getApplicationContext(), request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                boolean localAsset = url.startsWith("file:///android_asset/");
                boolean http = url.startsWith("http://") || url.startsWith("https://");
                boolean externalScheme = http || url.startsWith("mailto:")
                        || url.startsWith("tel:") || url.startsWith("geo:");
                boolean trusted = http && ServerConfig.isTrustedNavigationUrl(registry, url);
                NavigationPolicy.Decision decision = NavigationPolicy.decide(
                        localAsset, trusted, externalScheme,
                        request.isForMainFrame(), request.hasGesture());
                if (decision == NavigationPolicy.Decision.ALLOW) {
                    return false;
                }
                return decision == NavigationPolicy.Decision.OPEN_EXTERNAL
                        ? openExternal(url) : true;
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
                    setShellChromeVisible(false);
                    updateNavigationState();
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
                    activateNativeBridge(url);
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
                setShellChromeVisible(true);
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
                setLocalBridgeAttached(true);
                view.loadUrl(ERROR_URL);
                probeAlternative(failedModule, failedBase);
            }
        };
    }

    private void installNativeMessageBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(TAG, "WebView message listener unavailable; remote native capabilities disabled");
            return;
        }
        Set<String> originRules = new HashSet<>();
        for (AppModule module : registry.all()) {
            for (String base : ServerConfig.moduleUrls(registry, module.id)) {
                String rule = originRule(base);
                if (!rule.isEmpty()) originRules.add(rule);
            }
        }
        if (originRules.isEmpty()) {
            Log.w(TAG, "No module origins available for native message bridge");
            return;
        }
        try {
            WebViewCompat.addWebMessageListener(webView, "ShadowNative", originRules,
                    this::onNativeMessage);
        } catch (IllegalArgumentException error) {
            Log.e(TAG, "Invalid native bridge origin rules; bridge disabled", error);
        }
    }

    private String originRule(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return "";
            String displayHost = host.contains(":") ? "[" + host + "]" : host;
            int port = uri.getPort();
            boolean defaultPort = port == 80 && "http".equalsIgnoreCase(scheme)
                    || port == 443 && "https".equalsIgnoreCase(scheme);
            return scheme.toLowerCase() + "://" + displayHost.toLowerCase()
                    + (port >= 0 && !defaultPort ? ":" + port : "");
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private void activateNativeBridge(String url) {
        if (url == null || registry.isIdentityUrl(url)) {
            nativeBridgeSession.invalidate();
            return;
        }
        String moduleId = moduleIdForUrl(url);
        AppModule module = moduleId == null ? null : registry.get(moduleId);
        if (module == null) {
            nativeBridgeSession.invalidate();
            return;
        }
        Set<String> capabilities = new HashSet<>();
        for (int index = 0; index < module.capabilities.length(); index++) {
            String capability = module.capabilities.optString(index);
            if (!capability.isEmpty()) capabilities.add(capability);
        }
        byte[] entropy = new byte[32];
        secureRandom.nextBytes(entropy);
        String nonce = android.util.Base64.encodeToString(entropy,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP
                        | android.util.Base64.NO_PADDING);
        if (!nativeBridgeSession.beginDocument(moduleId, url,
                ServerConfig.moduleUrls(registry, moduleId), capabilities, nonce)) {
            return;
        }
        try {
            JSONObject descriptor = new JSONObject()
                    .put("schemaVersion", NativeBridgeSession.SCHEMA_VERSION)
                    .put("moduleId", moduleId)
                    .put("nonce", nonce)
                    .put("capabilities", new JSONArray(capabilities));
            String script = "(function(){'use strict';"
                    + "if(!window.ShadowNative||!window.ShadowNative.postMessage)return;"
                    + "const session=Object.freeze(" + descriptor + ");"
                    + "const pending=new Map();"
                    + "window.ShadowNative.onmessage=function(event){try{"
                    + "const reply=JSON.parse(event.data);const item=pending.get(reply.requestId);"
                    + "if(!item)return;pending.delete(reply.requestId);clearTimeout(item.timer);"
                    + "reply.ok?item.resolve(reply.result):item.reject(new Error(reply.error||'native_error'));"
                    + "}catch(_){}};"
                    + "const request=function(capability,operation,payload){return new Promise((resolve,reject)=>{"
                    + "const requestId=(self.crypto&&crypto.randomUUID?crypto.randomUUID().replace(/-/g,''):(Date.now().toString(36)+Math.random().toString(36).slice(2)+Math.random().toString(36).slice(2)));"
                    + "const timer=setTimeout(()=>{pending.delete(requestId);reject(new Error('native_timeout'));},15000);"
                    + "pending.set(requestId,{resolve:resolve,reject:reject,timer:timer});"
                    + "window.ShadowNative.postMessage(JSON.stringify({schemaVersion:session.schemaVersion,requestId:requestId,nonce:session.nonce,capability:capability,operation:operation,payload:payload||{}}));"
                    + "});};"
                    + "const bridge=Object.freeze({schemaVersion:session.schemaVersion,moduleId:session.moduleId,capabilities:session.capabilities,request:request});"
                    + "try{Object.defineProperty(window,'ShadowNativeSession',{value:session,configurable:true});"
                    + "Object.defineProperty(window,'ShadowNativeBridge',{value:bridge,configurable:true});}catch(_){return;}"
                    + "window.dispatchEvent(new CustomEvent('shadow-native-ready',{detail:bridge}));"
                    + "})();";
            webView.evaluateJavascript(script, null);
        } catch (JSONException ignored) {
            nativeBridgeSession.invalidate();
        }
    }

    private void onNativeMessage(WebView view, WebMessageCompat message, Uri sourceOrigin,
                                 boolean isMainFrame, JavaScriptReplyProxy replyProxy) {
        String raw = message.getData();
        if (raw == null || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128 * 1024) {
            replyNative(replyProxy, "", false, null, "invalid_message");
            return;
        }
        String requestId = "";
        try {
            JSONObject envelope = new JSONObject(raw);
            requireExactKeys(envelope, "schemaVersion", "requestId", "nonce",
                    "capability", "operation", "payload");
            requestId = envelope.getString("requestId");
            String operation = envelope.getString("operation");
            JSONObject payload = envelope.getJSONObject("payload");
            NativeBridgeSession.Authorization authorization = nativeBridgeSession.authorize(
                    sourceOrigin.toString(), isMainFrame, envelope.getInt("schemaVersion"),
                    requestId, envelope.getString("nonce"), envelope.getString("capability"),
                    operation);
            if (authorization != NativeBridgeSession.Authorization.ALLOWED) {
                Log.w(TAG, "native bridge request denied: " + authorization);
                replyNative(replyProxy, requestId, false, null,
                        "denied_" + authorization.name().toLowerCase());
                return;
            }
            Object result = dispatchNativeOperation(operation, payload);
            replyNative(replyProxy, requestId, true, result, null);
        } catch (JSONException | IllegalArgumentException | SecurityException error) {
            Log.w(TAG, "invalid native bridge payload", error);
            replyNative(replyProxy, requestId, false, null, "invalid_payload");
        } catch (RuntimeException error) {
            Log.e(TAG, "native bridge operation failed", error);
            replyNative(replyProxy, requestId, false, null, "native_failure");
        }
    }

    private Object dispatchNativeOperation(String operation, JSONObject payload)
            throws JSONException {
        switch (operation) {
            case "capture.get":
                requireExactKeys(payload);
                return pendingCaptureJson();
            case "capture.complete":
                requireExactKeys(payload, "captureId");
                completePendingCapture(payload.getString("captureId"));
                return new JSONObject();
            case "brief.show": {
                requireExactKeys(payload, "brief");
                JSONObject brief = payload.getJSONObject("brief");
                requireExactKeys(brief, "id", "title", "body", "notify");
                NexusNative.showBrief(this, brief.toString());
                return new JSONObject();
            }
            case "offline.enqueue":
                requireExactKeys(payload, "action");
                return new JSONObject().put("id",
                        NexusNative.enqueueAction(this, payload.getJSONObject("action").toString()));
            case "offline.list":
                requireExactKeys(payload);
                return new JSONArray(NexusNative.actionsJson(this));
            case "offline.complete":
                requireExactKeys(payload, "actionId");
                NexusNative.completeAction(this, payload.getString("actionId"));
                return new JSONObject();
            case "shell.openSettings":
                requireExactKeys(payload);
                mainHandler.post(this::showSettings);
                return new JSONObject();
            case "shell.openAppCenter":
                requireExactKeys(payload);
                mainHandler.post(this::openAppCenter);
                return new JSONObject();
            case "health.scale.start":
                requireExactKeys(payload);
                mainHandler.post(healthFeature::startTimedScaleScan);
                return new JSONObject();
            case "health.offline.open":
                requireExactKeys(payload);
                mainHandler.post(this::showHealthOffline);
                return new JSONObject();
            default:
                throw new IllegalArgumentException("unsupported operation");
        }
    }

    private JSONObject pendingCaptureJson() throws JSONException {
        PendingShareCapture capture = pendingShareCapture;
        if (!"nexus".equals(nativeBridgeSession.moduleId()) || capture == null) {
            return new JSONObject();
        }
        JSONObject value = new JSONObject()
                .put("capture_id", "cap_" + capture.id)
                .put("source_type", capture.sourceType)
                .put("text", capture.text)
                .put("source_app", capture.sourceApp);
        if (capture.stream != null) {
            value.put("file", new JSONObject()
                    .put("url", "/__shadow_app_capture/" + capture.id)
                    .put("name", capture.filename)
                    .put("type", capture.mimeType)
                    .put("size", capture.sizeBytes));
        }
        return value;
    }

    private void completePendingCapture(String captureId) {
        PendingShareCapture capture = pendingShareCapture;
        if ("nexus".equals(nativeBridgeSession.moduleId()) && capture != null
                && ("cap_" + capture.id).equals(captureId)) {
            pendingShareCapture = null;
        }
    }

    private void replyNative(JavaScriptReplyProxy proxy, String requestId, boolean ok,
                             Object result, String error) {
        try {
            JSONObject response = new JSONObject()
                    .put("schemaVersion", NativeBridgeSession.SCHEMA_VERSION)
                    .put("requestId", requestId)
                    .put("ok", ok);
            if (ok) response.put("result", result == null ? JSONObject.NULL : result);
            else response.put("error", error == null ? "native_error" : error);
            proxy.postMessage(response.toString());
        } catch (JSONException ignored) {
            proxy.postMessage("{\"schemaVersion\":1,\"requestId\":\"\",\"ok\":false,\"error\":\"native_error\"}");
        }
    }

    private void requireExactKeys(JSONObject value, String... allowed) throws JSONException {
        Set<String> expected = new HashSet<>(Arrays.asList(allowed));
        Set<String> actual = new HashSet<>();
        java.util.Iterator<String> keys = value.keys();
        while (keys.hasNext()) actual.add(keys.next());
        if (!actual.equals(expected)) throw new JSONException("unexpected fields");
    }

    private void setLocalBridgeAttached(boolean attached) {
        if (attached == localBridgeAttached) return;
        if (attached) webView.addJavascriptInterface(localShellBridge, "ShellBridge");
        else webView.removeJavascriptInterface("ShellBridge");
        localBridgeAttached = attached;
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
        AppModule home = registry.get(registry.homeModuleId());
        if (home != null && home.enabled) {
            openModule(home.id);
            return;
        }
        openAppCenter();
    }

    private void openAppCenter() {
        currentModuleId = null;
        currentPageUrl = HOME_URL;
        lastError = "";
        loadingErrorPage = false;
        erroredUrl = null;
        showingHealthOffline = false;
        showingHealthSnapshot = false;
        mainHandler.removeCallbacks(healthProbe);
        titleView.setText("应用中心");
        setShellChromeVisible(false);
        updateNavigationState();
        setLocalBridgeAttached(true);
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
        setShellChromeVisible(!"nexus".equals(module.id));
        titleView.setText(module.name);
        updateNavigationState();
        currentPageUrl = UrlTools.bare(targetUrl);
        setLocalBridgeAttached(false);
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
        setShellChromeVisible(!"nexus".equals(moduleId));
        updateNavigationState();
    }

    private void setShellChromeVisible(boolean visible) {
        if (shellToolbar != null) {
            shellToolbar.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void updateNavigationState() {
        String selected = null;
        if (HOME_URL.equals(currentPageUrl) && currentModuleId == null) {
            selected = "apps";
        } else if ("nexus".equals(currentModuleId)) {
            selected = "home";
        } else if ("health".equals(currentModuleId)) {
            selected = "health";
        }
        for (Map.Entry<String, NavItemRefs> entry : navigationItems.entrySet()) {
            boolean active = entry.getKey().equals(selected);
            NavItemRefs refs = entry.getValue();
            int color = active ? BRAND_CYAN : NAV_MUTED;
            refs.icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            refs.label.setTextColor(color);
            refs.label.setTypeface(Typeface.create(
                    active ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            if (active) {
                GradientDrawable indicator = new GradientDrawable();
                indicator.setColor(Color.rgb(20, 49, 58));
                indicator.setCornerRadius(dp(18));
                refs.icon.setBackground(indicator);
            } else {
                refs.icon.setBackgroundColor(Color.TRANSPARENT);
            }
        }
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
        setShellChromeVisible(true);
        updateNavigationState();
        setLocalBridgeAttached(true);
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
        } else if ("nexus".equals(currentModuleId)) {
            finishAfterTransition();
        } else {
            openHome();
        }
    }

    private boolean isNexusBridgeAllowed() {
        if (!"nexus".equals(currentModuleId) || currentPageUrl == null) {
            return false;
        }
        for (String candidate : ServerConfig.moduleUrls(registry, "nexus")) {
            String nexusUrl = UrlTools.bare(candidate);
            if (!nexusUrl.isEmpty() && UrlTools.isWithinBase(currentPageUrl, nexusUrl)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLocalBridgeAllowed(String... allowedPages) {
        if (!localBridgeAttached || currentPageUrl == null) return false;
        for (String allowed : allowedPages) {
            if (allowed.equals(currentPageUrl)) return true;
        }
        return false;
    }

    private void acceptShareIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_PROCESS_TEXT.equals(action)) {
            return;
        }
        CharSequence sharedText = Intent.ACTION_PROCESS_TEXT.equals(action)
                ? intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                : intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        ClipData clip = intent.getClipData();
        if (stream == null && clip != null && clip.getItemCount() > 0) {
            stream = clip.getItemAt(0).getUri();
            if (sharedText == null) {
                sharedText = clip.getItemAt(0).coerceToText(this);
            }
        }
        String text = sharedText == null ? "" : sharedText.toString().trim();
        if (text.isEmpty() && stream == null) {
            return;
        }
        String mimeType = intent.getType();
        if ((mimeType == null || mimeType.isEmpty()) && stream != null) {
            mimeType = getContentResolver().getType(stream);
        }
        if (mimeType == null || mimeType.isEmpty()) {
            mimeType = stream == null ? "text/plain" : "application/octet-stream";
        }
        String filename = stream == null ? "" : sharedDisplayName(stream);
        long size = stream == null ? 0 : sharedSize(stream);
        String sourceType = stream != null ? "android.share.file"
                : text.matches("^https?://\\S+$") ? "android.share.url" : "android.share.text";
        Uri referrer = getReferrer();
        pendingShareCapture = new PendingShareCapture(
                UUID.randomUUID().toString(), sourceType, text, stream,
                filename.isEmpty() ? "shared-file" : filename, mimeType, size,
                referrer == null ? "" : referrer.toString());
        mainHandler.post(() -> openModule("nexus"));
    }

    private String sharedDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                return value == null ? "" : value;
            }
        } catch (RuntimeException ignored) {
            // A provider may expose a stream without metadata.
        }
        return uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
    }

    private long sharedSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return Math.max(0, cursor.getLong(0));
            }
        } catch (RuntimeException ignored) {
            // Unknown size is allowed; the Nexus upload will enforce its own limit.
        }
        return 0;
    }

    private WebResourceResponse interceptSharedCapture(WebResourceRequest request) {
        PendingShareCapture capture = pendingShareCapture;
        String origin = request.getRequestHeaders().get("Origin");
        String referer = request.getRequestHeaders().get("Referer");
        boolean trustedInitiator = origin != null
                ? isNexusOrigin(origin) : referer != null && isNexusUrl(referer);
        if (capture == null || capture.stream == null || !"GET".equals(request.getMethod())
                || !isNexusBridgeAllowed() || !trustedInitiator
                || !UrlTools.sameOrigin(request.getUrl().toString(), currentPageUrl)
                || !request.getUrl().getPath().equals("/__shadow_app_capture/" + capture.id)) {
            return null;
        }
        try {
            InputStream input = getContentResolver().openInputStream(capture.stream);
            if (input == null) {
                return null;
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            headers.put("X-Content-Type-Options", "nosniff");
            return new WebResourceResponse(capture.mimeType, null, 200, "OK", headers, input);
        } catch (FileNotFoundException | SecurityException ignored) {
            return null;
        }
    }

    private boolean isNexusUrl(String url) {
        for (String candidate : ServerConfig.moduleUrls(registry, "nexus")) {
            if (UrlTools.isWithinBase(url, candidate)) return true;
        }
        return false;
    }

    private boolean isNexusOrigin(String url) {
        for (String candidate : ServerConfig.moduleUrls(registry, "nexus")) {
            if (UrlTools.sameOrigin(url, candidate)) return true;
        }
        return false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptShareIntent(intent);
    }

    private void showSettings() {
        TextView intro = new TextView(this);
        intro.setText(R.string.settings_device_intro);
        intro.setTextColor(Color.rgb(157, 176, 187));
        intro.setTextSize(13);
        intro.setLineSpacing(0, 1.25f);
        intro.setPadding(0, 0, 0, dp(12));

        Switch scale = new Switch(this);
        scale.setText("后台监听体脂秤");
        scale.setContentDescription("后台监听体脂秤");
        scale.setChecked(prefs.getBoolean(HealthFeature.KEY_SCALE_SCAN, false));

        Switch samsung = new Switch(this);
        samsung.setText(SamsungSync.isAvailable()
                ? "同步三星健康数据" : "三星健康 SDK 不可用");
        samsung.setEnabled(SamsungSync.isAvailable());
        samsung.setChecked(SamsungSync.isAvailable()
                && prefs.getBoolean(SamsungSync.PREF_ENABLED, false));

        Switch reminder = new Switch(this);
        reminder.setText(R.string.settings_daily_health_reminder);
        reminder.setChecked(prefs.getBoolean(Reminders.PREF_ENABLED, false));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), dp(4));
        content.addView(intro);
        content.addView(scale, settingsControlParams());
        content.addView(samsung, settingsControlParams());
        content.addView(reminder, settingsControlParams());

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("设备与同步")
                .setView(content)
                .setPositiveButton("保存", (dialog, which) -> {
                    healthFeature.applySettings(
                            prefs.getString(HealthFeature.KEY_INGEST_TOKEN, ""), scale.isChecked(),
                            prefs.getString(HealthFeature.KEY_SCALE_BINDKEY, ""),
                            samsung.isChecked(), reminder.isChecked());
                })
                .setNeutralButton("高级配置", (dialog, which) -> showAdvancedHealthSettings())
                .setNegativeButton("关闭", null);
        builder.show();
    }

    private LinearLayout.LayoutParams settingsControlParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
    }

    private void showAdvancedHealthSettings() {
        EditText token = new EditText(this);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setHint("健康服务 INGEST_TOKEN");
        token.setSingleLine(true);
        token.setText(prefs.getString(HealthFeature.KEY_INGEST_TOKEN, ""));

        EditText bindkey = new EditText(this);
        bindkey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        bindkey.setHint("小米 S400 bindkey（旧秤留空）");
        bindkey.setSingleLine(true);
        bindkey.setText(prefs.getString(HealthFeature.KEY_SCALE_BINDKEY, ""));

        TextView hint = new TextView(this);
        hint.setText("仅首次接入设备或轮换服务凭据时需要修改。内容只保存在本机，不进入云备份。");
        hint.setTextColor(Color.rgb(139, 157, 169));
        hint.setTextSize(12);
        hint.setLineSpacing(0, 1.2f);
        hint.setPadding(0, dp(12), 0, 0);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(6), dp(22), 0);
        content.addView(token);
        content.addView(bindkey);
        content.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle("高级健康配置")
                .setView(content)
                .setPositiveButton("保存", (dialog, which) -> healthFeature.applySettings(
                        token.getText().toString(),
                        prefs.getBoolean(HealthFeature.KEY_SCALE_SCAN, false),
                        bindkey.getText().toString(),
                        prefs.getBoolean(SamsungSync.PREF_ENABLED, false),
                        prefs.getBoolean(Reminders.PREF_ENABLED, false)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void download(String url, String userAgent, String contentDisposition,
                          String mimeType, long contentLength) {
        if (!ServerConfig.isTrustedNavigationUrl(registry, currentPageUrl)
                || (!url.startsWith("https://") && !url.startsWith("http://"))) {
            Toast.makeText(this, "已阻止不可信下载", Toast.LENGTH_SHORT).show();
            return;
        }
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
        nativeBridgeSession.invalidate();
        if (localBridgeAttached) webView.removeJavascriptInterface("ShellBridge");
        webView.destroy();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class ShellBridge {
        @JavascriptInterface
        public String getModules() {
            return isLocalBridgeAllowed(HOME_URL)
                    ? registry.clientJson(MainActivity.this) : "[]";
        }

        @JavascriptInterface
        public void openModule(String moduleId) {
            if (isLocalBridgeAllowed(HOME_URL)) {
                mainHandler.post(() -> MainActivity.this.openModule(moduleId));
            }
        }

        @JavascriptInterface
        public void openHome() {
            if (isLocalBridgeAllowed(HOME_URL, ERROR_URL, HEALTH_OFFLINE_URL)) {
                mainHandler.post(MainActivity.this::openHome);
            }
        }

        @JavascriptInterface
        public void openAppCenter() {
            if (isLocalBridgeAllowed(HOME_URL, ERROR_URL, HEALTH_OFFLINE_URL)) {
                mainHandler.post(MainActivity.this::openAppCenter);
            }
        }

        @JavascriptInterface
        public void openSettings() {
            if (isLocalBridgeAllowed(HOME_URL, ERROR_URL, HEALTH_OFFLINE_URL)) {
                mainHandler.post(MainActivity.this::showSettings);
            }
        }

        /** Legacy alias used by the health offline page. */
        @JavascriptInterface
        public void changeAddress() {
            if (isLocalBridgeAllowed(HEALTH_OFFLINE_URL)) openSettings();
        }

        @JavascriptInterface
        public void openOfflinePage() {
            if (isLocalBridgeAllowed(HEALTH_OFFLINE_URL)) {
                mainHandler.post(MainActivity.this::showHealthOffline);
            }
        }

        @JavascriptInterface
        public void probeNow() {
            if (isLocalBridgeAllowed(HEALTH_OFFLINE_URL)) {
                mainHandler.post(healthProbe);
            }
        }

        @JavascriptInterface
        public String getBootstrap() {
            return isLocalBridgeAllowed(HEALTH_OFFLINE_URL)
                    ? HealthOffline.bootstrap(MainActivity.this) : "";
        }

        @JavascriptInterface
        public String getOfflineStatus() {
            return isLocalBridgeAllowed(HEALTH_OFFLINE_URL)
                    ? HealthOffline.status(MainActivity.this, lastError) : "{}";
        }

        @JavascriptInterface
        public int enqueueRecord(String type, String payloadJson) {
            return isLocalBridgeAllowed(HEALTH_OFFLINE_URL)
                    ? HealthOffline.enqueue(MainActivity.this, type, payloadJson) : -1;
        }

        @JavascriptInterface
        public int setQueuedHabit(int habitId, int doneCount) {
            return isLocalBridgeAllowed(HEALTH_OFFLINE_URL)
                    ? HealthOffline.setQueuedHabit(MainActivity.this, habitId, doneCount) : -1;
        }

        @JavascriptInterface
        public String getQueuedHabits() {
            return isLocalBridgeAllowed(HEALTH_OFFLINE_URL)
                    ? HealthOffline.queuedHabits(MainActivity.this) : "{}";
        }

        @JavascriptInterface
        public void retry() {
            if (isLocalBridgeAllowed(ERROR_URL)) mainHandler.post(() -> {
                if (currentModuleId == null) {
                    openHome();
                } else {
                    openModule(currentModuleId);
                }
            });
        }

        @JavascriptInterface
        public String getError() {
            if (!isLocalBridgeAllowed(ERROR_URL)) return "{}";
            try {
                return new JSONObject().put("message", lastError).toString();
            } catch (JSONException ignored) {
                return "{}";
            }
        }

        /** Compatibility contract used by the existing shadow-health pages. */
        @JavascriptInterface
        public void startScaleScan() {
            if (!isLocalBridgeAllowed(HEALTH_OFFLINE_URL)) {
                return;
            }
            mainHandler.post(healthFeature::startTimedScaleScan);
        }
    }
}
