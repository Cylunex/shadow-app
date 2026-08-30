package com.shadow.app.core;

/** Pure decision helper for WebView navigations. */
public final class NavigationPolicy {
    public enum Decision {
        ALLOW,
        BLOCK,
        OPEN_EXTERNAL
    }

    private NavigationPolicy() {
    }

    /**
     * Untrusted redirects and iframe navigations are blocked. Only a user-initiated main-frame
     * navigation may leave the shell through an external app.
     */
    public static Decision decide(boolean localAsset, boolean trustedHttp,
                                  boolean externalSchemeAllowed,
                                  boolean mainFrame, boolean hasGesture) {
        if (localAsset || trustedHttp) {
            return Decision.ALLOW;
        }
        if (!externalSchemeAllowed || !mainFrame || !hasGesture) {
            return Decision.BLOCK;
        }
        return Decision.OPEN_EXTERNAL;
    }
}
