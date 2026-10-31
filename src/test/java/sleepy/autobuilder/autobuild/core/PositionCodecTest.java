package sleepy.autobuilder.autobuild.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PositionCodecTest {
    @Test
    void roundTripsPositiveAndNegativeCoordinates() {
        int[][] positions = {
                {0, 0, 0},
                {1, 64, -1},
                {-30_000_000, -64, 30_000_000},
                {30_000_000, 255, -30_000_000},
                {-1, 255, -1}
        };
        for (int[] pos : positions) {
            long packed = PositionCodec.pack(pos[0], pos[1], pos[2]);
            assertEquals(pos[0], PositionCodec.unpackX(packed));
            assertEquals(pos[1], PositionCodec.unpackY(packed));
            assertEquals(pos[2], PositionCodec.unpackZ(packed));
        }
    }

    @Test
    void setEncodingIsExactAndOrdered() {
        Set<Long> positions = new LinkedHashSet<>();
        positions.add(PositionCodec.pack(1, 2, 3));
        positions.add(PositionCodec.pack(-1, -2, -3));
        positions.add(PositionCodec.pack(30_000_000, 255, -30_000_000));
        String encoded = PositionCodec.encode(positions);
        assertEquals(positions, PositionCodec.decode(encoded));
    }

    @Test
    void emptyPayloadIsEmptySet() {
        assertEquals(Set.of(), PositionCodec.decode(""));
        assertEquals("", PositionCodec.encode(Set.of()));
    }
}
