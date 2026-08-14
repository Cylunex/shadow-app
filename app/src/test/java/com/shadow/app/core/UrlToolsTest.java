package com.shadow.app.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UrlToolsTest {
    @Test
    public void normalizeBaseAddsSchemeAndRemovesTrailingSlash() {
        assertEquals("http://192.168.1.10:55080",
                UrlTools.normalizeBase(" 192.168.1.10:55080/// "));
    }

    @Test
    public void normalizeBaseRejectsInvalidInput() {
        assertEquals("", UrlTools.normalizeBase(""));
        assertEquals("", UrlTools.normalizeBase("not a host"));
    }

    @Test
    public void joinPreservesModuleTrailingSlash() {
        assertEquals("https://example.com/shealth/",
                UrlTools.join("https://example.com/", "/shealth/"));
    }

    @Test
    public void withPortBuildsNasModuleOrigin() {
        assertEquals("http://192.168.1.100:55080",
                UrlTools.withPort("http://192.168.1.100", 55080));
    }

    @Test
    public void withPortPreservesCredentialsAndCommonPath() {
        assertEquals("https://user:pass@example.com:8443/shadow",
                UrlTools.withPort("https://user:pass@example.com/shadow", 8443));
    }

    @Test
    public void bareRemovesOnlyUserInfo() {
        assertEquals("https://example.com:8443/stock/",
                UrlTools.bare("https://user:pass@example.com:8443/stock/"));
    }

    @Test
    public void sameOriginNormalizesDefaultPorts() {
        assertTrue(UrlTools.sameOrigin("https://example.com/a", "https://example.com:443/b"));
        assertFalse(UrlTools.sameOrigin("https://example.com", "http://example.com"));
    }
}
