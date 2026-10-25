package com.mahanapps.omnideck.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class UploadWriterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void testSuccessfulUpload() throws IOException {
        File dir = temp.newFolder();
        File target = new File(dir, "uploaded.txt");
        byte[] content = "Hello TV Storage!".getBytes(StandardCharsets.UTF_8);

        StorageGuard guard = new StorageGuard(new StorageGuard.FreeSpaceSupplier() {
            @Override
            public long getTotalBytes() { return 10000L * 1024L * 1024L; }
            @Override
            public long getAvailableBytes() { return 5000L * 1024L * 1024L; }
        });

        UploadWriter.UploadResult res = UploadWriter.writeStream(
                new ByteArrayInputStream(content),
                content.length,
                target,
                guard
        );

        assertNotNull(res);
        assertTrue(target.exists());
        assertEquals(content.length, res.bytesWritten);
        assertNotNull(res.sha256Hex);
    }

    @Test(expected = IOException.class)
    public void testExceedsCapacityRejection() throws IOException {
        File dir = temp.newFolder();
        File target = new File(dir, "large.bin");

        // Available is exactly at floor (500 MB)
        StorageGuard guard = new StorageGuard(new StorageGuard.FreeSpaceSupplier() {
            @Override
            public long getTotalBytes() { return 1000L * 1024L * 1024L; }
            @Override
            public long getAvailableBytes() { return 500L * 1024L * 1024L; }
        });

        byte[] content = new byte[1024];
        UploadWriter.writeStream(new ByteArrayInputStream(content), content.length, target, guard);
    }
}
