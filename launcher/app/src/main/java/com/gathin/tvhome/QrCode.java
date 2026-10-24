package com.gathin.tvhome;

import android.graphics.Bitmap;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A QR code for a short text (the photo upload address), so a phone's camera opens it. Byte mode, error
 * correction level M, versions 1 to 6 (up to 106 bytes) and mask 0, which every reader accepts. Follows the
 * steps of Project Nayuki's reference encoder (MIT), cut down to what one URL needs.
 */
final class QrCode {
    /** Error-correction codewords per block and the number of blocks, level M, versions 1 to 6. */
    private static final int[] ECC_PER_BLOCK = {10, 16, 26, 18, 24, 16};
    private static final int[] BLOCKS = {1, 1, 1, 2, 2, 4};
    private static final int MAX_VERSION = 6;
    /** Level M's two format bits. */
    private static final int FORMAT_M = 0;
    private static final int QUIET_ZONE = 4;

    private final int version, size;
    private final boolean[][] modules, function;

    private QrCode(int version) {
        this.version = version;
        size = version * 4 + 17;
        modules = new boolean[size][size];
        function = new boolean[size][size];
    }

    /** The code for text, or null when it is too long for version 6. */
    static QrCode encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int v = 1; v <= MAX_VERSION; v++) {
            int capacity = dataCodewords(v) * 8;
            if (4 + 8 + data.length * 8 <= capacity) {
                QrCode qr = new QrCode(v);
                qr.drawFunctionPatterns();
                qr.drawCodewords(qr.withEcc(qr.dataBytes(data, capacity)));
                qr.applyMask();
                qr.drawFormatBits();
                return qr;
            }
        }
        return null;
    }

    int size() {
        return size;
    }

    boolean dark(int x, int y) {
        return modules[y][x];
    }

    /** Black modules on white, with the quiet zone around, each module scale pixels square. */
    Bitmap bitmap(int scale) {
        int side = (size + QUIET_ZONE * 2) * scale;
        int[] pixels = new int[side * side];
        Arrays.fill(pixels, 0xFFFFFFFF);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!modules[y][x]) continue;
                for (int dy = 0; dy < scale; dy++) {
                    int row = ((y + QUIET_ZONE) * scale + dy) * side + (x + QUIET_ZONE) * scale;
                    Arrays.fill(pixels, row, row + scale, 0xFF000000);
                }
            }
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888);
    }

    // ---- sizes --------------------------------------------------------------------------------------------

    private static int rawModules(int v) {
        int result = (16 * v + 128) * v + 64;
        if (v >= 2) {
            int align = v / 7 + 2;
            result -= (25 * align - 10) * align - 55;
        }
        return result;
    }

    private static int dataCodewords(int v) {
        return rawModules(v) / 8 - ECC_PER_BLOCK[v - 1] * BLOCKS[v - 1];
    }

    // ---- the fixed patterns -------------------------------------------------------------------------------

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        if (version >= 2) {
            // Versions 2 to 6 have one alignment pattern, near the bottom right.
            int at = size - 7;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) set(at + dx, at + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        drawFormatBits();   // reserves the format areas; drawn again with the real bits after masking
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy, d = Math.max(Math.abs(dx), Math.abs(dy));
                if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, d != 2 && d != 4);
            }
        }
    }

    private static boolean bit(int value, int i) {
        return ((value >>> i) & 1) != 0;
    }

    /** Level M and mask 0, with their BCH check bits, beside the finders; and the dark module. */
    private void drawFormatBits() {
        int data = FORMAT_M << 3;   // mask 0
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    // ---- the data -----------------------------------------------------------------------------------------

    /** Mode, length, the bytes, the terminator and the padding, as codewords. */
    private byte[] dataBytes(byte[] data, int capacityBits) {
        byte[] out = new byte[capacityBits / 8];
        int[] at = {0};
        append(out, at, 0x4, 4);
        append(out, at, data.length, 8);
        for (byte b : data) append(out, at, b & 0xFF, 8);
        append(out, at, 0, Math.min(4, capacityBits - at[0]));
        at[0] = (at[0] + 7) / 8 * 8;
        for (int pad = 0xEC; at[0] < capacityBits; pad ^= 0xEC ^ 0x11) append(out, at, pad, 8);
        return out;
    }

    private static void append(byte[] out, int[] at, int value, int bits) {
        for (int i = bits - 1; i >= 0; i--, at[0]++) {
            if (bit(value, i)) out[at[0] >>> 3] |= (byte) (0x80 >>> (at[0] & 7));
        }
    }

    /** Splits the data into blocks, adds each block's Reed-Solomon codewords, and interleaves them. */
    private byte[] withEcc(byte[] data) {
        int blocks = BLOCKS[version - 1], eccLen = ECC_PER_BLOCK[version - 1];
        int raw = rawModules(version) / 8;
        int shortBlocks = blocks - raw % blocks, shortLen = raw / blocks;
        byte[] divisor = divisor(eccLen);
        byte[][] all = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            byte[] dat = Arrays.copyOfRange(data, k, k + shortLen - eccLen + (i < shortBlocks ? 0 : 1));
            k += dat.length;
            byte[] block = Arrays.copyOf(dat, shortLen + 1);
            byte[] ecc = remainder(dat, divisor);
            System.arraycopy(ecc, 0, block, block.length - eccLen, ecc.length);
            all[i] = block;
        }
        byte[] out = new byte[raw];
        for (int i = 0, k = 0; i < all[0].length; i++) {
            for (int j = 0; j < blocks; j++) {
                if (i != shortLen - eccLen || j >= shortBlocks) out[k++] = all[j][i];
            }
        }
        return out;
    }

    private static byte[] divisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) multiply(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = multiply(root, 0x02);
        }
        return result;
    }

    private static byte[] remainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) multiply(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    /** Multiplication in GF(2^8) with the QR polynomial 0x11D. */
    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    /** The codewords in the zigzag order: two columns at a time, up then down, from the right. */
    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    /** Mask 0: every module where x + y is even is inverted. */
    private void applyMask() {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!function[y][x] && (x + y) % 2 == 0) modules[y][x] = !modules[y][x];
            }
        }
    }
}
