package sleepy.autobuilder.autobuild.core;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Packed long position codec compatible with Minecraft's BlockPos packing. Used by progress files so
 * negative coordinates round-trip exactly.
 */
public final class PositionCodec {
    private static final int XZ_MASK = 0x3FFFFFF;
    private static final int Y_MASK = 0xFFF;

    private PositionCodec() {
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x & XZ_MASK) << 38) | ((long) (z & XZ_MASK) << 12) | (long) (y & Y_MASK);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    public static int unpackY(long packed) {
        return (int) (packed << 52 >> 52);
    }

    public static int unpackZ(long packed) {
        return (int) (packed << 26 >> 38);
    }

    public static String encode(Set<Long> positions) {
        if (positions == null || positions.isEmpty()) return "";
        long[] values = new long[positions.size()];
        int i = 0;
        for (Long value : positions) values[i++] = value == null ? 0L : value;
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Long.BYTES);
        for (long value : values) buffer.putLong(value);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    public static Set<Long> decode(String encoded) {
        Set<Long> result = new LinkedHashSet<>();
        if (encoded == null || encoded.isBlank()) return result;
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length % Long.BYTES != 0) throw new IllegalArgumentException("Invalid position payload length");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.remaining() >= Long.BYTES) result.add(buffer.getLong());
        return result;
    }
}
