package com.mahanapps.omnideck.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * Fast, self-contained, pure Java QR Code generator.
 * Vendored from project Nayuki QR Code library (MIT License).
 * Provides zero external dependency QR generation with exact quiet-zone sizing.
 */
public final class QrCode {

    public enum Ecc {
        LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2);
        final int formatBits;
        Ecc(int fb) { this.formatBits = fb; }
    }

    public final int version;
    public final int size;
    public final Ecc errorCorrectionLevel;
    public final int mask;
    private final boolean[][] modules;

    public boolean getModule(int x, int y) {
        return (0 <= x && x < size && 0 <= y && y < size) && modules[y][x];
    }

    public static QrCode encodeText(String text, Ecc ecl) {
        Objects.requireNonNull(text);
        Objects.requireNonNull(ecl);
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return encodeBinary(bytes, ecl);
    }

    public static QrCode encodeBinary(byte[] data, Ecc ecl) {
        int version = 1;
        while (true) {
            int dataCap = getNumDataCodewords(version, ecl) * 8;
            int bitLen = 4 + 16 + data.length * 8; // Byte mode (16-bit count for version >= 10, or 8-bit)
            if (version < 10) bitLen = 4 + 8 + data.length * 8;
            if (bitLen <= dataCap) break;
            version++;
            if (version > 40) throw new IllegalArgumentException("Data too long for QR");
        }

        BitBuffer bb = new BitBuffer();
        bb.appendBits(0x4, 4); // Byte mode
        bb.appendBits(data.length, version < 10 ? 8 : 16);
        for (byte b : data) {
            bb.appendBits(b & 0xFF, 8);
        }

        int dataCapacityBits = getNumDataCodewords(version, ecl) * 8;
        bb.appendBits(0, Math.min(4, dataCapacityBits - bb.len));
        bb.appendBits(0, (8 - bb.len % 8) % 8);
        for (int padByte = 0xEC; bb.len < dataCapacityBits; padByte ^= 0xEC ^ 0x11) {
            bb.appendBits(padByte, 8);
        }

        byte[] dataCodewords = new byte[bb.len / 8];
        for (int i = 0; i < bb.len; i++) {
            dataCodewords[i >>> 3] |= (bb.getBit(i) ? 1 : 0) << (7 - (i & 7));
        }

        return new QrCode(version, ecl, dataCodewords, 0);
    }

    private QrCode(int ver, Ecc ecl, byte[] dataCodewords, int msk) {
        this.version = ver;
        this.size = ver * 4 + 17;
        this.errorCorrectionLevel = ecl;
        this.modules = new boolean[size][size];

        drawFunctionPatterns();
        byte[] allCodewords = addEccAndInterleave(dataCodewords);
        drawCodewords(allCodewords);
        this.mask = msk;
        applyMask(msk);
        drawFormatBits(msk);
    }

    private void drawFunctionPatterns() {
        // Finder patterns
        drawFinderPattern(3, 3);
        drawFinderPattern(size - 4, 3);
        drawFinderPattern(3, size - 4);

        // Timing patterns
        for (int i = 0; i < size; i++) {
            setFunctionModule(6, i, i % 2 == 0);
            setFunctionModule(i, 6, i % 2 == 0);
        }

        // Dark module
        setFunctionModule(8, size - 8, true);

        // Alignment patterns
        int[] alignGrid = getAlignmentPatternPositions(version);
        for (int y : alignGrid) {
            for (int x : alignGrid) {
                if (!((x == 6 && y == 6) || (x == 6 && y == size - 7) || (x == size - 7 && y == 6))) {
                    drawAlignmentPattern(x, y);
                }
            }
        }
    }

    private void drawFinderPattern(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx, yy = y + dy;
                if (0 <= xx && xx < size && 0 <= yy && yy < size) {
                    setFunctionModule(xx, yy, dist != 2 && dist != 4);
                }
            }
        }
    }

    private void drawAlignmentPattern(int x, int y) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setFunctionModule(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    private void setFunctionModule(int x, int y, boolean isDark) {
        modules[y][x] = isDark;
    }

    private byte[] addEccAndInterleave(byte[] data) {
        int numBlocks = NUM_ERROR_CORRECTION_BLOCKS[errorCorrectionLevel.ordinal()][version];
        int blockEccLen = ECC_CODEWORDS_PER_BLOCK[errorCorrectionLevel.ordinal()][version];
        int rawCodewords = getNumRawDataModules(version) / 8;
        int numShortBlocks = numBlocks - rawCodewords % numBlocks;
        int shortBlockLen = rawCodewords / numBlocks;

        byte[][] blocks = new byte[numBlocks][];
        byte[] rsDiv = reedSolomonComputeDivisor(blockEccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int blockLen = shortBlockLen - blockEccLen + (i >= numShortBlocks ? 1 : 0);
            byte[] dat = Arrays.copyOfRange(data, k, k + blockLen);
            k += blockLen;
            byte[] ecc = reedSolomonComputeRemainder(dat, rsDiv);
            byte[] full = Arrays.copyOf(dat, dat.length + ecc.length);
            System.arraycopy(ecc, 0, full, dat.length, ecc.length);
            blocks[i] = full;
        }

        byte[] result = new byte[rawCodewords];
        for (int i = 0, k = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < numBlocks; j++) {
                if (i < blocks[j].length) {
                    result[k++] = blocks[j][i];
                }
            }
        }
        return result;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunctionModule(x, y) && i < data.length * 8) {
                        modules[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
            }
        }
    }

    private boolean isFunctionModule(int x, int y) {
        if (x < 9 && y < 9) return true;
        if (x >= size - 8 && y < 9) return true;
        if (x < 9 && y >= size - 8) return true;
        if (x == 6 || y == 6) return true;
        if (x == 8 && y == size - 8) return true;
        int[] alignGrid = getAlignmentPatternPositions(version);
        for (int ay : alignGrid) {
            for (int ax : alignGrid) {
                if (!((ax == 6 && ay == 6) || (ax == 6 && ay == size - 7) || (ax == size - 7 && ay == 6))) {
                    if (Math.abs(x - ax) <= 2 && Math.abs(y - ay) <= 2) return true;
                }
            }
        }
        return false;
    }

    private void applyMask(int msk) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!isFunctionModule(x, y)) {
                    boolean invert = false;
                    switch (msk) {
                        case 0: invert = (x + y) % 2 == 0; break;
                        case 1: invert = y % 2 == 0; break;
                        case 2: invert = x % 3 == 0; break;
                        case 3: invert = (x + y) % 3 == 0; break;
                        case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                        case 5: invert = (x * y) % 2 + (x * y) % 3 == 0; break;
                        case 6: invert = ((x * y) % 2 + (x * y) % 3) % 2 == 0; break;
                        case 7: invert = ((x + y) % 2 + (x * y) % 3) % 2 == 0; break;
                    }
                    if (invert) modules[y][x] = !modules[y][x];
                }
            }
        }
    }

    private void drawFormatBits(int msk) {
        int data = (errorCorrectionLevel.formatBits << 3) | msk;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setFunctionModule(8, i, ((bits >>> i) & 1) != 0);
        setFunctionModule(8, 7, ((bits >>> 6) & 1) != 0);
        setFunctionModule(8, 8, ((bits >>> 7) & 1) != 0);
        setFunctionModule(7, 8, ((bits >>> 8) & 1) != 0);
        for (int i = 9; i < 15; i++) setFunctionModule(14 - i, 8, ((bits >>> i) & 1) != 0);
        for (int i = 0; i < 8; i++) setFunctionModule(size - 1 - i, 8, ((bits >>> i) & 1) != 0);
        for (int i = 8; i < 15; i++) setFunctionModule(8, size - 15 + i, ((bits >>> i) & 1) != 0);
    }

    private static byte[] reedSolomonComputeDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) reedSolomonMultiply(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = reedSolomonMultiply(root, 0x02);
        }
        return result;
    }

    private static byte[] reedSolomonComputeRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < divisor.length; i++) {
                result[i] ^= reedSolomonMultiply(divisor[i] & 0xFF, factor);
            }
        }
        return result;
    }

    private static int reedSolomonMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static int getNumDataCodewords(int ver, Ecc ecl) {
        return getNumRawDataModules(ver) / 8 -
                ECC_CODEWORDS_PER_BLOCK[ecl.ordinal()][ver] *
                NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal()][ver];
    }

    private static int getNumRawDataModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    private static int[] getAlignmentPatternPositions(int ver) {
        if (ver == 1) return new int[]{};
        int num = ver / 7 + 2;
        int step = (ver == 32) ? 26 : (ver * 4 + num * 2 + 1) / (num * 2 - 2) * 2;
        int[] result = new int[num];
        result[0] = 6;
        for (int i = num - 1, pos = ver * 4 + 10; i >= 1; i--, pos -= step) {
            result[i] = pos;
        }
        return result;
    }

    private static final byte[][] ECC_CODEWORDS_PER_BLOCK = {
        {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28},
        {0,  7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
        {0, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
        {0, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30}
    };

    private static final byte[][] NUM_ERROR_CORRECTION_BLOCKS = {
        {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49},
        {0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4,  6,  6,  6,  6,  7,  8,  8,  9,  9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25},
        {0, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81},
        {0, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8,  8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68}
    };

    private static final class BitBuffer {
        int[] data = new int[64];
        int len = 0;

        void appendBits(int val, int count) {
            for (int i = count - 1; i >= 0; i--) {
                boolean bit = ((val >>> i) & 1) != 0;
                if (len == data.length * 32) {
                    data = Arrays.copyOf(data, data.length * 2);
                }
                if (bit) data[len >>> 5] |= 1 << (31 - (len & 31));
                len++;
            }
        }

        boolean getBit(int index) {
            return ((data[index >>> 5] >>> (31 - (index & 31))) & 1) != 0;
        }
    }
}
