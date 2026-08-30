package com.shadow.app.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NativeBridgeSessionTest {
    private static final String NONCE =
            "0123456789abcdef0123456789abcdef01234567";

    private NativeBridgeSession nexusSession() {
        NativeBridgeSession session = new NativeBridgeSession();
        assertTrue(session.beginDocument(
                "nexus",
                "https://nexus.example.com/app/inbox",
                Arrays.asList("https://nexus.example.com/app/"),
                new HashSet<>(Arrays.asList("web", "media", "notification", "operations")),
                NONCE));
        return session;
    }

    @Test
    public void maliciousIframeCannotUseTopLevelSession() {
        NativeBridgeSession session = nexusSession();
        assertEquals(NativeBridgeSession.Authorization.NOT_MAIN_FRAME,
                session.authorize("https://nexus.example.com", false, 1,
                        "iframe_request_0001", NONCE, "media", "capture.get"));
    }

    @Test
    public void sourceOriginAndModulePathMustBothMatch() {
        NativeBridgeSession session = nexusSession();
        assertEquals(NativeBridgeSession.Authorization.SOURCE_MISMATCH,
                session.authorize("https://evil.example.com", true, 1,
                        "foreign_request_001", NONCE, "media", "capture.get"));

        NativeBridgeSession outsidePath = new NativeBridgeSession();
        assertTrue(!outsidePath.beginDocument(
                "nexus", "https://nexus.example.com/other",
                Arrays.asList("https://nexus.example.com/app/"),
                new HashSet<>(Arrays.asList("media")), NONCE));
    }

    @Test
    public void schemaNonceAndCapabilityAreRequired() {
        NativeBridgeSession session = nexusSession();
        assertEquals(NativeBridgeSession.Authorization.INVALID_SCHEMA,
                session.authorize("https://nexus.example.com", true, 2,
                        "schema_request_001", NONCE, "media", "capture.get"));
        assertEquals(NativeBridgeSession.Authorization.INVALID_NONCE,
                session.authorize("https://nexus.example.com", true, 1,
                        "nonce_request_0001", NONCE + "x", "media", "capture.get"));
        assertEquals(NativeBridgeSession.Authorization.CAPABILITY_MISMATCH,
                session.authorize("https://nexus.example.com", true, 1,
                        "capability_req_001", NONCE, "web", "capture.get"));
    }

    @Test
    public void repeatedRequestIsRejected() {
        NativeBridgeSession session = nexusSession();
        assertEquals(NativeBridgeSession.Authorization.ALLOWED,
                session.authorize("https://nexus.example.com", true, 1,
                        "replay_request_001", NONCE, "operations", "offline.list"));
        assertEquals(NativeBridgeSession.Authorization.REPLAYED,
                session.authorize("https://nexus.example.com", true, 1,
                        "replay_request_001", NONCE, "operations", "offline.list"));
    }

    @Test
    public void capabilityDoesNotLetAnotherModuleBorrowNexusOperations() {
        NativeBridgeSession notifications = new NativeBridgeSession();
        assertTrue(notifications.beginDocument(
                "notifications", "https://notify.example.com/inbox",
                Arrays.asList("https://notify.example.com/"),
                new HashSet<>(Arrays.asList("web", "notification", "operations")), NONCE));
        assertEquals(NativeBridgeSession.Authorization.WRONG_MODULE,
                notifications.authorize("https://notify.example.com", true, 1,
                        "wrong_module_req_01", NONCE, "operations", "offline.list"));
    }

    @Test
    public void processRestoreRejectsPreviousDocumentNonce() {
        NativeBridgeSession beforeProcessDeath = nexusSession();
        assertEquals(NativeBridgeSession.Authorization.ALLOWED,
                beforeProcessDeath.authorize("https://nexus.example.com", true, 1,
                        "before_restart_001", NONCE, "operations", "offline.list"));

        NativeBridgeSession restoredProcess = new NativeBridgeSession();
        assertEquals(NativeBridgeSession.Authorization.NO_ACTIVE_DOCUMENT,
                restoredProcess.authorize("https://nexus.example.com", true, 1,
                        "after_restart_0001", NONCE, "operations", "offline.list"));
    }
}
