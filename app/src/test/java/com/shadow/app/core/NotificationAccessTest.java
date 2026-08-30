package com.shadow.app.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NotificationAccessTest {
    @Test
    public void android13PermissionRevocationDisablesForegroundAndWorkerNotifications() {
        assertFalse(NotificationAccess.allows(33, false, true));
    }

    @Test
    public void systemLevelAppToggleIsAlsoRespected() {
        assertFalse(NotificationAccess.allows(35, true, false));
    }

    @Test
    public void preAndroid13DoesNotRequireRuntimePermission() {
        assertTrue(NotificationAccess.allows(32, false, true));
    }
}
