package com.shadow.app.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UrlToolsTest {
    @Test
    public void normalizeBaseAddsSchemeAndRemovesTrailingSlash() {
        assertEquals("http://192.0.2.10:18080",
                UrlTools.normalizeBase(" 192.0.2.10:18080/// "));
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
        assertEquals("http://192.0.2.100:18080",
                UrlTools.withPort("http://192.0.2.100", 18080));
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

    @Test
    public void moduleBaseMatchingRespectsSubpathBoundaries() {
        assertTrue(UrlTools.isWithinBase(
                "https://example.com/travel/maps/1", "https://example.com/travel/"));
        assertTrue(UrlTools.isWithinBase(
                "https://example.com/travel", "https://example.com/travel/"));
        assertFalse(UrlTools.isWithinBase(
                "https://example.com/travelogue", "https://example.com/travel/"));
        assertFalse(UrlTools.isWithinBase(
                "https://other.example.com/travel/", "https://example.com/travel/"));
    }
}
