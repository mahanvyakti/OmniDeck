package com.mahanapps.omnideck.core;

import java.io.File;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates and validates single-use, 60-second download tickets.
 */
public final class DownloadTicketStore {

    private static final long TICKET_TTL_MS = 60 * 1000L; // 60 seconds

    private static final class TicketEntry {
        final File file;
        final long expiresAtMs;

        TicketEntry(File file, long expiresAtMs) {
            this.file = file;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, TicketEntry> tickets = new ConcurrentHashMap<>();

    public String createTicket(File file) {
        cleanExpired();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        String ticketId = sb.toString();
        tickets.put(ticketId, new TicketEntry(file, System.currentTimeMillis() + TICKET_TTL_MS));
        return ticketId;
    }

    /**
     * Consumes a ticket (single use). Returns File if valid and not expired, or null.
     */
    public File consumeTicket(String ticketId) {
        if (ticketId == null) return null;
        TicketEntry entry = tickets.remove(ticketId);
        if (entry != null && System.currentTimeMillis() < entry.expiresAtMs) {
            return entry.file;
        }
        return null;
    }

    private void cleanExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, TicketEntry>> it = tickets.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAtMs < now) {
                it.remove();
            }
        }
    }
}
