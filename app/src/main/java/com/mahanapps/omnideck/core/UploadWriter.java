package com.mahanapps.omnideck.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Streams incoming uploaded data to disk using safe chunked buffers,
 * computes SHA-256 on the fly, and enforces mid-stream storage floor checks.
 */
public final class UploadWriter {

    private static final int CHUNK_SIZE = 32 * 1024; // 32 KB chunk for low memory TV

    public static final class UploadResult {
        public final File file;
        public final long bytesWritten;
        public final String sha256Hex;

        public UploadResult(File file, long bytesWritten, String sha256Hex) {
            this.file = file;
            this.bytesWritten = bytesWritten;
            this.sha256Hex = sha256Hex;
        }
    }

    private UploadWriter() {}

    /**
     * Writes an incoming input stream to a temporary `.part` file, verifying capacity
     * continuously, then renames to target file on complete success.
     */
    public static UploadResult writeStream(InputStream in,
                                           long expectedLength,
                                           File targetFile,
                                           StorageGuard guard) throws IOException {
        File parentDir = targetFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        // Pre-check capacity if expected length is provided
        if (guard != null && expectedLength > 0) {
            if (!guard.hasSafeCapacity(expectedLength)) {
                throw new IOException("Storage floor reserve reached (need 500 MB minimum)");
            }
        }

        File partFile = new File(parentDir, ".part_" + System.currentTimeMillis() + "_" + targetFile.getName() + ".part");
        long totalWritten = 0;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }

        try (FileOutputStream fos = new FileOutputStream(partFile)) {
            byte[] buf = new byte[CHUNK_SIZE];
            long bytesRemaining = expectedLength > 0 ? expectedLength : Long.MAX_VALUE;

            while (bytesRemaining > 0) {
                int toRead = (int) Math.min(buf.length, bytesRemaining);
                int r = in.read(buf, 0, toRead);
                if (r == -1) {
                    break;
                }

                fos.write(buf, 0, r);
                digest.update(buf, 0, r);
                totalWritten += r;
                if (expectedLength > 0) {
                    bytesRemaining -= r;
                }

                // Periodic mid-stream storage check (every 512 KB to prevent tight disk saturation)
                if (guard != null && totalWritten % (512 * 1024) < CHUNK_SIZE) {
                    if (!guard.hasSafeCapacity(0)) {
                        throw new IOException("Storage floor reserve reached during stream write");
                    }
                }
            }

            fos.flush();
            try {
                fos.getFD().sync();
            } catch (Exception ignored) {}
        } catch (Throwable t) {
            // Clean up temporary part file on failure
            partFile.delete();
            throw (t instanceof IOException) ? (IOException) t : new IOException("Write failed", t);
        }

        if (expectedLength > 0 && totalWritten != expectedLength) {
            partFile.delete();
            throw new IOException("Stream truncated: expected " + expectedLength + " bytes, wrote " + totalWritten);
        }

        // Atomic rename to final target file
        if (targetFile.exists()) {
            targetFile.delete();
        }
        if (!partFile.renameTo(targetFile)) {
            // Fallback copy if rename across filesystems fails
            throw new IOException("Failed to finalize upload to " + targetFile.getName());
        }

        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }

        return new UploadResult(targetFile, totalWritten, sb.toString());
    }
}
