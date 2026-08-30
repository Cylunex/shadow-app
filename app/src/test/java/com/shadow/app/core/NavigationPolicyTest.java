package com.shadow.app.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NavigationPolicyTest {
    @Test
    public void trustedMainFrameAndBundledAssetsStayInWebView() {
        assertEquals(NavigationPolicy.Decision.ALLOW,
                NavigationPolicy.decide(false, true, true, true, false));
        assertEquals(NavigationPolicy.Decision.ALLOW,
                NavigationPolicy.decide(true, false, false, true, false));
    }

    @Test
    public void untrustedRedirectIsBlockedWithoutLaunchingExternalApp() {
        assertEquals(NavigationPolicy.Decision.BLOCK,
                NavigationPolicy.decide(false, false, true, true, false));
    }

    @Test
    public void maliciousIframeNavigationIsBlockedEvenWithGesture() {
        assertEquals(NavigationPolicy.Decision.BLOCK,
                NavigationPolicy.decide(false, false, true, false, true));
    }

    @Test
    public void userInitiatedMainFrameExternalLinkLeavesShell() {
        assertEquals(NavigationPolicy.Decision.OPEN_EXTERNAL,
                NavigationPolicy.decide(false, false, true, true, true));
    }

    @Test
    public void localFileAndContentSchemesCannotEscapeAssetBoundary() {
        assertEquals(NavigationPolicy.Decision.BLOCK,
                NavigationPolicy.decide(false, false, false, true, true));
    }
}
