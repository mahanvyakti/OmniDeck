package com.mahanapps.omnideck.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class StorageGuardTest {

    @Test
    public void testMinimumFloorEnforced() {
        // 4 GB total, 600 MB free -> floor is at least 500 MB (or 10% = 400 MB -> 500 MB wins)
        StorageGuard guard = new StorageGuard(new StorageGuard.FreeSpaceSupplier() {
            @Override
            public long getTotalBytes() {
                return 4000L * 1024L * 1024L;
            }

            @Override
            public long getAvailableBytes() {
                return 600L * 1024L * 1024L;
            }
        });

        assertEquals(500L * 1024L * 1024L, guard.getMinimumFloorBytes());
        assertTrue(guard.hasSafeCapacity(50L * 1024L * 1024L)); // 600 - 50 = 550 MB >= 500 MB
        assertFalse(guard.hasSafeCapacity(150L * 1024L * 1024L)); // 600 - 150 = 450 MB < 500 MB
        assertEquals(100L * 1024L * 1024L, guard.getUsableUploadBytes());
    }
}
