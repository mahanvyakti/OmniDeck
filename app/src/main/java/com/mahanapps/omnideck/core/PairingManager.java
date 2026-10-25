package com.mahanapps.omnideck.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Manages physical-presence PIN pairing, rate limiting, and multi-device Bearer token tracking.
 * Captures device hostnames and supports selective revocation from TV UI.
 */
public final class PairingManager {

    private static final long PAIRING_WINDOW_MS = 5 * 60 * 1000L; // 5 minutes
    private static final long LOCKOUT_MS = 60 * 1000L; // 60 seconds
    public static final long INACTIVITY_TIMEOUT_MS = 2 * 60 * 1000L; // 2 minutes (120s)
    private static final int MAX_FAILED_PER_IP = 5;
    private static final int MAX_TOKENS = 10; // Up to 10 concurrent paired devices

    public static final class PairedDevice {
        public final String tokenHash;
        public final String clientId;
        public final String label;
        public final String hostname;
        public final String osType;
        public final String model;
        public final String browser;
        public final String ipAddress;
        public final long connectedAtMs;
        public long lastActiveMs;

        public PairedDevice(String tokenHash, String clientId, String label, String hostname, String osType, String model, String browser, String ipAddress, long connectedAtMs, long lastActiveMs) {
            this.tokenHash = tokenHash;
            this.clientId = (clientId != null && !clientId.isEmpty()) ? clientId : (ipAddress + "_" + (label != null ? label : "dev"));
            this.label = (label != null && !label.isEmpty() && !"K".equals(label)) ? label : (model != null && !model.isEmpty() ? model : "Companion Device");
            this.hostname = hostname != null ? hostname : ipAddress;
            this.osType = osType != null ? osType : "Device";
            this.model = model != null ? model : "";
            this.browser = browser != null ? browser : "Browser";
            this.ipAddress = ipAddress;
            this.connectedAtMs = connectedAtMs;
            this.lastActiveMs = lastActiveMs;
        }

        public PairedDevice(String tokenHash, String label, String hostname, String osType, String model, String browser, String ipAddress, long connectedAtMs, long lastActiveMs) {
            this(tokenHash, null, label, hostname, osType, model, browser, ipAddress, connectedAtMs, lastActiveMs);
        }

        public PairedDevice(String tokenHash, String label, String hostname, String osType, String ipAddress, long connectedAtMs, long lastActiveMs) {
            this(tokenHash, null, label, hostname, osType, label, "Browser", ipAddress, connectedAtMs, lastActiveMs);
        }

        public long getConnectedDurationSeconds() {
            return Math.max(0, (System.currentTimeMillis() - connectedAtMs) / 1000L);
        }

        public String getInitials() {
            String target = (label != null && !label.isEmpty() && !"K".equals(label)) ? label : (model != null && !model.isEmpty() ? model : osType);
            if (target != null && !target.isEmpty()) {
                String clean = target.replaceAll("[^a-zA-Z0-9]", " ").trim();
                String[] words = clean.split("\\s+");
                if (words.length >= 2 && words[0].length() > 0 && words[1].length() > 0) {
                    return ("" + words[0].charAt(0) + words[1].charAt(0)).toUpperCase();
                } else if (words.length == 1 && words[0].length() >= 2) {
                    return words[0].substring(0, 2).toUpperCase();
                } else if (words.length == 1 && words[0].length() == 1 && !"K".equals(words[0])) {
                    return words[0].toUpperCase();
                }
            }
            if ("apple".equalsIgnoreCase(osType) || "ios".equalsIgnoreCase(osType) || "macos".equalsIgnoreCase(osType)) return "AP";
            if ("windows".equalsIgnoreCase(osType)) return "PC";
            if ("android".equalsIgnoreCase(osType)) return "AN";
            return "DV";
        }
    }

    private static final class IpAttempt {
        int count;
        long lockedUntilMs;
    }

    private final SecureRandom random = new SecureRandom();
    private String currentPin = null;
    private long pinExpiryMs = 0;
    private final Map<String, IpAttempt> ipAttempts = new HashMap<>();
    private final List<PairedDevice> pairedDevices = new ArrayList<>();

    public synchronized String openPairingWindow() {
        int code = 100000 + random.nextInt(900000);
        this.currentPin = String.valueOf(code);
        this.pinExpiryMs = System.currentTimeMillis() + PAIRING_WINDOW_MS;
        return currentPin;
    }

    public synchronized void closePairingWindow() {
        this.currentPin = null;
        this.pinExpiryMs = 0;
    }

    public synchronized boolean isPairingWindowOpen() {
        return currentPin != null && System.currentTimeMillis() < pinExpiryMs;
    }

    public synchronized String getCurrentPin() {
        return isPairingWindowOpen() ? currentPin : null;
    }

    public synchronized long getPinRemainingSeconds() {
        if (!isPairingWindowOpen()) {
            return 0;
        }
        return Math.max(0, (pinExpiryMs - System.currentTimeMillis()) / 1000L);
    }

    public synchronized String verifyPinAndIssueToken(String sourceIp, String submittedPin, String deviceLabel) {
        return verifyPinAndIssueToken(sourceIp, submittedPin, deviceLabel, null);
    }

    /**
     * Attempts to verify PIN and issue a Bearer token with hostname capture.
     */
    public synchronized String verifyPinAndIssueToken(String sourceIp, String submittedPin, String deviceLabel, String clientHostname) {
        return verifyPinAndIssueToken(sourceIp, submittedPin, deviceLabel, clientHostname, null, null, null);
    }

    public synchronized String verifyPinAndIssueToken(String sourceIp, String submittedPin, String deviceLabel, String clientHostname, String osType, String model, String browser) {
        return verifyPinAndIssueToken(sourceIp, submittedPin, deviceLabel, clientHostname, osType, model, browser, null);
    }

    public synchronized String verifyPinAndIssueToken(String sourceIp, String submittedPin, String deviceLabel, String clientHostname, String osType, String model, String browser, String clientId) {
        long now = System.currentTimeMillis();
        purgeInactiveDevices();
        IpAttempt attempt = ipAttempts.computeIfAbsent(sourceIp, k -> new IpAttempt());

        if (attempt.lockedUntilMs > now) {
            return null; // locked out
        }

        if (!isPairingWindowOpen() || submittedPin == null) {
            return null;
        }

        if (currentPin.equals(submittedPin.trim())) {
            // Success
            attempt.count = 0;
            attempt.lockedUntilMs = 0;

            byte[] tokenBytes = new byte[16];
            random.nextBytes(tokenBytes);
            String token = toHex(tokenBytes);
            String hash = hashToken(token);

            String cleanLabel = (deviceLabel != null && !deviceLabel.trim().isEmpty() && !"K".equals(deviceLabel.trim()))
                    ? deviceLabel.trim()
                    : ((model != null && !model.isEmpty()) ? model : "Companion Device");
            String os = (osType != null && !osType.isEmpty()) ? osType : detectOs(cleanLabel);
            String finalHostname = (clientHostname != null && !clientHostname.trim().isEmpty())
                    ? clientHostname.trim()
                    : sourceIp;
            String finalClientId = (clientId != null && !clientId.trim().isEmpty())
                    ? clientId.trim()
                    : (sourceIp + "#" + cleanLabel);

            // Deduplication: Remove any previous session matching this client ID or matching same IP and device label
            Iterator<PairedDevice> it = pairedDevices.iterator();
            while (it.hasNext()) {
                PairedDevice existing = it.next();
                boolean sameClient = finalClientId.equals(existing.clientId);
                boolean sameIpAndLabel = sourceIp.equals(existing.ipAddress) && cleanLabel.equalsIgnoreCase(existing.label);
                if (sameClient || sameIpAndLabel) {
                    it.remove();
                }
            }

            // Cap at MAX_TOKENS: remove oldest if limit reached
            if (pairedDevices.size() >= MAX_TOKENS) {
                pairedDevices.remove(0);
            }

            pairedDevices.add(new PairedDevice(hash, finalClientId, cleanLabel, finalHostname, os, model, browser, sourceIp, now, now));
            return token;
        } else {
            // Fail
            attempt.count++;
            if (attempt.count >= MAX_FAILED_PER_IP) {
                attempt.lockedUntilMs = now + LOCKOUT_MS;
            }
            return null;
        }
    }

    public synchronized void purgeInactiveDevices() {
        long cutoff = System.currentTimeMillis() - INACTIVITY_TIMEOUT_MS;
        Iterator<PairedDevice> it = pairedDevices.iterator();
        while (it.hasNext()) {
            PairedDevice d = it.next();
            if (d.lastActiveMs < cutoff) {
                it.remove();
            }
        }
    }

    public synchronized boolean isValidToken(String token) {
        if (token == null || token.length() != 32) {
            return false;
        }
        purgeInactiveDevices();
        String hash = hashToken(token);
        long now = System.currentTimeMillis();
        for (PairedDevice d : pairedDevices) {
            if (d.tokenHash.equals(hash)) {
                d.lastActiveMs = now;
                return true;
            }
        }
        return false;
    }

    public synchronized boolean revokeDeviceByToken(String token) {
        if (token == null) return false;
        String hash = hashToken(token);
        return revokeDeviceByHash(hash);
    }

    public synchronized boolean revokeDeviceByIp(String ip) {
        if (ip == null) return false;
        boolean removed = false;
        Iterator<PairedDevice> it = pairedDevices.iterator();
        while (it.hasNext()) {
            if (ip.equals(it.next().ipAddress)) {
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    public synchronized boolean revokeDeviceByHash(String hash) {
        if (hash == null) return false;
        Iterator<PairedDevice> it = pairedDevices.iterator();
        while (it.hasNext()) {
            if (hash.equals(it.next().tokenHash)) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    public synchronized void revokeAll() {
        pairedDevices.clear();
        closePairingWindow();
    }

    public synchronized List<PairedDevice> getPairedDevices() {
        purgeInactiveDevices();
        return new ArrayList<>(pairedDevices);
    }

    private static String detectOs(String label) {
        String l = label.toLowerCase();
        if (l.contains("iphone") || l.contains("ipad") || l.contains("ios")) return "apple";
        if (l.contains("mac") || l.contains("darwin") || l.contains("os x")) return "apple";
        if (l.contains("windows") || l.contains("win32") || l.contains("win64")) return "windows";
        if (l.contains("android")) return "android";
        if (l.contains("linux")) return "linux";
        if (l.contains("cros") || l.contains("chrome")) return "chrome";
        return "device";
    }

    private static String hashToken(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes());
            return toHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 missing", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
