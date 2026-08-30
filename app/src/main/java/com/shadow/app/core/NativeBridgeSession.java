package com.shadow.app.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-document authorization state for the asynchronous native message bridge.
 *
 * <p>The Android adapter additionally constrains which origins receive the JavaScript object.
 * This class performs the checks that must remain true at message time: main frame, exact source
 * origin, module base path, schema, nonce, one-shot request id, operation and declared capability.
 */
public final class NativeBridgeSession {
    public static final int SCHEMA_VERSION = 1;

    public enum Authorization {
        ALLOWED,
        NO_ACTIVE_DOCUMENT,
        NOT_MAIN_FRAME,
        SOURCE_MISMATCH,
        INVALID_SCHEMA,
        INVALID_REQUEST_ID,
        INVALID_NONCE,
        UNKNOWN_OPERATION,
        WRONG_MODULE,
        CAPABILITY_MISMATCH,
        CAPABILITY_NOT_DECLARED,
        REPLAYED,
        REQUEST_LIMIT_EXCEEDED
    }

    private static final int MAX_SEEN_REQUESTS = 256;
    private static final Map<String, String> OPERATION_CAPABILITIES;
    private static final Map<String, String> OPERATION_MODULES;

    static {
        Map<String, String> values = new HashMap<>();
        values.put("capture.get", "media");
        values.put("capture.complete", "media");
        values.put("brief.show", "notification");
        values.put("offline.enqueue", "operations");
        values.put("offline.list", "operations");
        values.put("offline.complete", "operations");
        values.put("shell.openSettings", "web");
        values.put("shell.openAppCenter", "web");
        values.put("health.scale.start", "health.scale");
        values.put("health.offline.open", "web");
        OPERATION_CAPABILITIES = Collections.unmodifiableMap(values);

        Map<String, String> modules = new HashMap<>();
        modules.put("capture.get", "nexus");
        modules.put("capture.complete", "nexus");
        modules.put("brief.show", "nexus");
        modules.put("offline.enqueue", "nexus");
        modules.put("offline.list", "nexus");
        modules.put("offline.complete", "nexus");
        modules.put("shell.openSettings", "nexus");
        modules.put("shell.openAppCenter", "nexus");
        modules.put("health.scale.start", "health");
        modules.put("health.offline.open", "health");
        OPERATION_MODULES = Collections.unmodifiableMap(modules);
    }

    private String moduleId;
    private String pageUrl;
    private String nonce;
    private List<String> moduleBases = Collections.emptyList();
    private Set<String> capabilities = Collections.emptySet();
    private final Set<String> seenRequestIds = new HashSet<>();

    public synchronized boolean beginDocument(String newModuleId, String newPageUrl,
                                              List<String> newModuleBases,
                                              Set<String> newCapabilities, String newNonce) {
        invalidate();
        if (newModuleId == null || newPageUrl == null || newNonce == null
                || newNonce.length() < 32 || newNonce.length() > 128) {
            return false;
        }
        boolean withinBase = false;
        for (String base : newModuleBases) {
            if (UrlTools.isWithinBase(newPageUrl, base)) {
                withinBase = true;
                break;
            }
        }
        if (!withinBase) {
            return false;
        }
        moduleId = newModuleId;
        pageUrl = newPageUrl;
        moduleBases = Collections.unmodifiableList(new java.util.ArrayList<>(newModuleBases));
        capabilities = Collections.unmodifiableSet(new HashSet<>(newCapabilities));
        nonce = newNonce;
        return true;
    }

    public synchronized void invalidate() {
        moduleId = null;
        pageUrl = null;
        nonce = null;
        moduleBases = Collections.emptyList();
        capabilities = Collections.emptySet();
        seenRequestIds.clear();
    }

    public synchronized Authorization authorize(String sourceOrigin, boolean mainFrame,
                                                int schemaVersion, String requestId,
                                                String suppliedNonce, String capability,
                                                String operation) {
        if (moduleId == null || pageUrl == null || nonce == null) {
            return Authorization.NO_ACTIVE_DOCUMENT;
        }
        if (!mainFrame) {
            return Authorization.NOT_MAIN_FRAME;
        }
        if (!UrlTools.sameOrigin(sourceOrigin, pageUrl) || !withinCurrentModule(pageUrl)) {
            return Authorization.SOURCE_MISMATCH;
        }
        if (schemaVersion != SCHEMA_VERSION) {
            return Authorization.INVALID_SCHEMA;
        }
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,96}")) {
            return Authorization.INVALID_REQUEST_ID;
        }
        if (!constantTimeEquals(nonce, suppliedNonce)) {
            return Authorization.INVALID_NONCE;
        }
        String required = OPERATION_CAPABILITIES.get(operation);
        if (required == null) {
            return Authorization.UNKNOWN_OPERATION;
        }
        if (!moduleId.equals(OPERATION_MODULES.get(operation))) {
            return Authorization.WRONG_MODULE;
        }
        if (!required.equals(capability)) {
            return Authorization.CAPABILITY_MISMATCH;
        }
        if (!capabilities.contains(required)) {
            return Authorization.CAPABILITY_NOT_DECLARED;
        }
        if (seenRequestIds.contains(requestId)) {
            return Authorization.REPLAYED;
        }
        if (seenRequestIds.size() >= MAX_SEEN_REQUESTS) {
            return Authorization.REQUEST_LIMIT_EXCEEDED;
        }
        seenRequestIds.add(requestId);
        return Authorization.ALLOWED;
    }

    public synchronized String moduleId() {
        return moduleId;
    }

    public synchronized String nonce() {
        return nonce;
    }

    public synchronized Set<String> capabilities() {
        return capabilities;
    }

    public static String requiredCapability(String operation) {
        return OPERATION_CAPABILITIES.get(operation);
    }

    private boolean withinCurrentModule(String url) {
        for (String base : moduleBases) {
            if (UrlTools.isWithinBase(url, base)) {
                return true;
            }
        }
        return false;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
