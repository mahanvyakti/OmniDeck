package com.mahanapps.omnideck.core;

import java.io.File;

/**
 * Enforces a strict storage floor to prevent exhausting TV internal flash memory.
 */
public final class StorageGuard {

    public interface FreeSpaceSupplier {
        long getTotalBytes();
        long getAvailableBytes();
    }

    private final FreeSpaceSupplier supplier;
    private final long minimumFloorBytes;

    public StorageGuard(FreeSpaceSupplier supplier) {
        this.supplier = supplier;
        long total = supplier.getTotalBytes();
        // Reserve at least 500 MB or 10% of total storage
        this.minimumFloorBytes = Math.max(500L * 1024L * 1024L, total / 10L);
    }

    public long getMinimumFloorBytes() {
        return minimumFloorBytes;
    }

    public long getAvailableBytes() {
        return supplier.getAvailableBytes();
    }

    public long getTotalBytes() {
        return supplier.getTotalBytes();
    }

    public long getUsableUploadBytes() {
        long avail = supplier.getAvailableBytes();
        if (avail >= minimumFloorBytes) {
            return Math.max(0L, avail - minimumFloorBytes);
        }
        long constrainedFloor = Math.max(100L * 1024L * 1024L, avail / 2L);
        return Math.max(0L, avail - constrainedFloor);
    }

    /**
     * Checks if the device has enough space for an incoming upload while retaining the floor.
     */
    public boolean hasSafeCapacity(long incomingBytes) {
        if (incomingBytes < 0) {
            return false;
        }
        long avail = supplier.getAvailableBytes();
        if (avail >= minimumFloorBytes) {
            return (avail - incomingBytes) >= minimumFloorBytes;
        }
        // If device available storage is already below the standard floor (< 500 MB),
        // adaptively protect at least half of remaining storage or 100 MB headroom.
        long constrainedFloor = Math.max(100L * 1024L * 1024L, avail / 2L);
        return (avail - incomingBytes) >= constrainedFloor;
    }

    /**
     * Deletes orphan `.part` files older than maxAgeMs in a directory.
     *
     * @return Number of orphan files deleted.
     */
    public static int sweepOrphanParts(File dir, long maxAgeMs) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return 0;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".part") || name.startsWith(".part_"));
        if (files == null) {
            return 0;
        }
        int count = 0;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (maxAgeMs <= 0 || (now - f.lastModified()) > maxAgeMs) {
                if (f.delete()) {
                    count++;
                }
            }
        }
        return count;
    }
}
