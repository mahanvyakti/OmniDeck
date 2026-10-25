package com.mahanapps.omnideck.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class PairingManagerTest {

    @Test
    public void testPinGenerationAndVerification() {
        PairingManager pm = new PairingManager();
        assertFalse(pm.isPairingWindowOpen());

        String pin = pm.openPairingWindow();
        assertTrue(pm.isPairingWindowOpen());
        assertNotNull(pin);
        assertEquals(6, pin.length());

        // Incorrect pin fails
        String tokenWrong = pm.verifyPinAndIssueToken("192.168.1.10", "000000", "Phone");
        assertNull(tokenWrong);

        // Correct pin succeeds
        String token = pm.verifyPinAndIssueToken("192.168.1.10", pin, "Phone");
        assertNotNull(token);
        assertEquals(32, token.length());
        assertTrue(pm.isValidToken(token));

        // Tracks paired device
        assertEquals(1, pm.getPairedDevices().size());
        PairingManager.PairedDevice dev = pm.getPairedDevices().get(0);
        assertEquals("Phone", dev.label);
        assertEquals("192.168.1.10", dev.ipAddress);
    }

    @Test
    public void testRateLimitingLockout() {
        PairingManager pm = new PairingManager();
        pm.openPairingWindow();

        for (int i = 0; i < 5; i++) {
            pm.verifyPinAndIssueToken("192.168.1.99", "999999", "Attacker");
        }

        // 6th attempt even with correct pin should be locked out
        String pin = pm.getCurrentPin();
        String token = pm.verifyPinAndIssueToken("192.168.1.99", pin, "Attacker");
        assertNull(token);
    }

    @Test
    public void testDeduplicationSameClient() {
        PairingManager pm = new PairingManager();
        String pin = pm.openPairingWindow();

        // Pair device first time
        String token1 = pm.verifyPinAndIssueToken("192.168.1.50", pin, "Samsung Galaxy S25 Ultra", "galaxy.local", "Android", "SM-S938", "Chrome", "client_phone_123");
        assertNotNull(token1);
        assertEquals(1, pm.getPairedDevices().size());

        // Re-pair from same client ID (e.g. page refresh or reconnect)
        String token2 = pm.verifyPinAndIssueToken("192.168.1.50", pin, "Samsung Galaxy S25 Ultra", "galaxy.local", "Android", "SM-S938", "Chrome", "client_phone_123");
        assertNotNull(token2);
        assertNotEquals(token1, token2); // New session token issued

        // MUST be deduplicated: size must remain exactly 1, not 2
        assertEquals(1, pm.getPairedDevices().size());
        assertEquals("Samsung Galaxy S25 Ultra", pm.getPairedDevices().get(0).label);
        assertTrue(pm.isValidToken(token2));
        assertFalse(pm.isValidToken(token1)); // Old token replaced

        // Re-pair with same IP and label even without client ID
        String token3 = pm.verifyPinAndIssueToken("192.168.1.50", pin, "Samsung Galaxy S25 Ultra", "galaxy.local", "Android", "SM-S938", "Chrome", null);
        assertNotNull(token3);
        assertEquals(1, pm.getPairedDevices().size());
    }

    @Test
    public void testDifferentClientsTrackSeparately() {
        PairingManager pm = new PairingManager();
        String pin = pm.openPairingWindow();

        // Pair Phone
        String tPhone = pm.verifyPinAndIssueToken("192.168.1.50", pin, "Samsung Galaxy S25 Ultra", "phone.local", "Android", "SM-S938", "Chrome", "client_phone");
        assertNotNull(tPhone);

        // Pair PC (different IP & client ID)
        String tPC = pm.verifyPinAndIssueToken("192.168.1.100", pin, "Windows 11 PC", "desktop.local", "Windows", "Desktop", "Chrome", "client_pc");
        assertNotNull(tPC);

        // Both distinct devices must be tracked
        assertEquals(2, pm.getPairedDevices().size());
        assertTrue(pm.isValidToken(tPhone));
        assertTrue(pm.isValidToken(tPC));
    }

    @Test
    public void testInactivityExpiration() {
        PairingManager pm = new PairingManager();
        String pin = pm.openPairingWindow();

        String token = pm.verifyPinAndIssueToken("192.168.1.50", pin, "Laptop", "laptop.local", "macOS", "MacBook", "Safari", "client_mac");
        assertNotNull(token);
        assertEquals(1, pm.getPairedDevices().size());

        // Simulate 3 minutes passing without activity (> 2 minutes timeout)
        PairingManager.PairedDevice dev = pm.getPairedDevices().get(0);
        dev.lastActiveMs = System.currentTimeMillis() - (PairingManager.INACTIVITY_TIMEOUT_MS + 5000L);

        // getPairedDevices() should automatically prune inactive device
        assertEquals(0, pm.getPairedDevices().size());

        // Token should now be invalid because device expired
        assertFalse(pm.isValidToken(token));
    }

    @Test
    public void testRevokeDeviceByToken() {
        PairingManager pm = new PairingManager();
        String pin = pm.openPairingWindow();

        String t1 = pm.verifyPinAndIssueToken("192.168.1.50", pin, "Device 1", "d1.local", "Android", "D1", "Chrome", "c1");
        String t2 = pm.verifyPinAndIssueToken("192.168.1.51", pin, "Device 2", "d2.local", "iOS", "D2", "Safari", "c2");
        assertEquals(2, pm.getPairedDevices().size());

        // Revoke only Device 1 (e.g. via beforeunload beacon or logout)
        assertTrue(pm.revokeDeviceByToken(t1));
        assertFalse(pm.isValidToken(t1));
        assertTrue(pm.isValidToken(t2));
        assertEquals(1, pm.getPairedDevices().size());
        assertEquals("Device 2", pm.getPairedDevices().get(0).label);
    }
}
