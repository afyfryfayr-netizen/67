package sleepy.autobuilder.autobuild.storage;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StorageEntryTest {
    @Test
    void constructorKeepsIndependentIdentityAndCoordinates() {
        StorageEntry first = new StorageEntry("Main chest", new BlockPos(10, 64, -20),
                "minecraft:overworld", StorageType.CHEST);
        StorageEntry second = new StorageEntry("Main chest", new BlockPos(10, 64, -20),
                "minecraft:overworld", StorageType.CHEST);

        assertNotEquals(first.id, second.id);
        assertEquals("10, 64, -20", first.coords());
        assertEquals("overworld", first.shortDimension());
        assertEquals(StorageType.CHEST, first.type);
        assertTrue(first.exactContainer);
        assertTrue(first.enabled);
        assertEquals(new BlockPos(10, 64, -20), first.pos());
    }

    @Test
    void dimensionMatchingAcceptsBlankAndRejectsOtherDimensions() {
        StorageEntry any = new StorageEntry("Any", BlockPos.ORIGIN, "", StorageType.UNKNOWN);
        StorageEntry overworld = new StorageEntry("Overworld", BlockPos.ORIGIN,
                "minecraft:overworld", StorageType.CHEST);

        assertTrue(any.dimensionMatches("minecraft:the_nether"));
        assertTrue(overworld.dimensionMatches("minecraft:overworld"));
        assertFalse(overworld.dimensionMatches("minecraft:the_end"));
        assertEquals("any", any.shortDimension());
        assertFalse(any.exactContainer);
    }

    @Test
    void statusTextReportsVisitSearchAndFoundMaterials() {
        StorageEntry entry = new StorageEntry("Stash", BlockPos.ORIGIN,
                "minecraft:overworld", StorageType.BARREL);
        assertEquals("Not visited", entry.statusText());

        entry.visited = true;
        entry.lastVisitedEpochMs = System.currentTimeMillis();
        entry.lastSearchedEpochMs = entry.lastVisitedEpochMs;
        entry.lastFound = Map.of("minecraft:oak_planks", 64);
        entry.lastStatus = "found materials";

        String status = entry.statusText();
        assertTrue(status.startsWith("Visited "));
        assertTrue(status.contains("searched "));
        assertTrue(status.contains("oak_planks x64"));
        assertFalse(status.contains("found materials"));
    }

    @Test
    void storageTypeParsingIsTolerantAndNeverThrows() {
        assertEquals(StorageType.CHEST, StorageType.parse("chest"));
        assertEquals(StorageType.TRAPPED_CHEST, StorageType.parse("trapped-chest"));
        assertEquals(StorageType.SHULKER_BOX, StorageType.parse("shulker box"));
        assertEquals(StorageType.UNKNOWN, StorageType.parse("not a real type"));
        assertEquals(StorageType.UNKNOWN, StorageType.parse(null));
        assertTrue(StorageType.CHEST.isContainer());
        assertFalse(StorageType.UNKNOWN.isContainer());
    }
}
