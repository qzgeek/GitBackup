package cn.qzgeek.gitbackup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnvilRegionTest {
    @TempDir Path tmp;

    @Test void readsPaddedRegion() throws Exception {
        Map<Integer, byte[]> records = new LinkedHashMap<>();
        records.put(AnvilRegion.slotIndex(0, 0), TestFixtures.record(TestFixtures.chunkNbt(0, 0, new byte[]{1, 2, 3})));
        records.put(AnvilRegion.slotIndex(5, 7), TestFixtures.record(TestFixtures.chunkNbt(5, 7, new byte[]{4, 5})));
        Path file = TestFixtures.writeRegion(tmp.resolve("r.0.0.mca"), records, true);

        Map<Integer, AnvilRegion.Slot> slots = AnvilRegion.readSlots(file);
        assertEquals(2, slots.size());
        assertArrayEquals(records.get(AnvilRegion.slotIndex(5, 7)), slots.get(AnvilRegion.slotIndex(5, 7)).bytes());
        assertEquals(5, slots.get(AnvilRegion.slotIndex(5, 7)).chunkXOffset());
        assertEquals(7, slots.get(AnvilRegion.slotIndex(5, 7)).chunkZOffset());
    }

    /** 本服务器上真实存在的"不补齐尾部"格式必须能读，这是最初踩到的坑。 */
    @Test void readsUnpaddedTail() throws Exception {
        Map<Integer, byte[]> records = new LinkedHashMap<>();
        records.put(AnvilRegion.slotIndex(1, 2), TestFixtures.record(TestFixtures.chunkNbt(1, 2, new byte[100])));
        Path file = TestFixtures.writeRegion(tmp.resolve("r.0.0.mca"), records, false);
        assertFalse(Files.size(file) % AnvilRegion.SECTOR == 0, "测试数据本身必须是不补齐的");
        Map<Integer, AnvilRegion.Slot> slots = AnvilRegion.readSlots(file);
        assertEquals(1, slots.size());
        assertArrayEquals(records.get(AnvilRegion.slotIndex(1, 2)), slots.get(AnvilRegion.slotIndex(1, 2)).bytes());
    }

    @Test void inPlaceReplaceKeepsHeaderAndOtherChunks() throws Exception {
        Map<Integer, byte[]> records = new LinkedHashMap<>();
        records.put(AnvilRegion.slotIndex(0, 0), TestFixtures.record(TestFixtures.chunkNbt(0, 0, new byte[]{9})));
        records.put(AnvilRegion.slotIndex(3, 3), TestFixtures.record(TestFixtures.chunkNbt(3, 3, new byte[]{8})));
        Path file = TestFixtures.writeRegion(tmp.resolve("r.0.0.mca"), records, true);
        byte[] headerBefore = new byte[8192];
        System.arraycopy(Files.readAllBytes(file), 0, headerBefore, 0, 8192);

        byte[] replacement = TestFixtures.record(TestFixtures.chunkNbt(0, 0, new byte[]{7, 7, 7}));
        AnvilRegion.replaceInPlace(file, Map.of(AnvilRegion.slotIndex(0, 0), replacement));

        byte[] after = Files.readAllBytes(file);
        byte[] headerAfter = new byte[8192];
        System.arraycopy(after, 0, headerAfter, 0, 8192);
        assertArrayEquals(headerBefore, headerAfter, "就地替换必须不改动目录表，否则服务端缓存会失效");
        Map<Integer, AnvilRegion.Slot> slots = AnvilRegion.readSlots(file);
        assertArrayEquals(replacement, slots.get(AnvilRegion.slotIndex(0, 0)).bytes());
        assertArrayEquals(records.get(AnvilRegion.slotIndex(3, 3)), slots.get(AnvilRegion.slotIndex(3, 3)).bytes());
    }

    @Test void inPlaceReplaceRefusesOversizedRecord() throws Exception {
        Map<Integer, byte[]> records = new LinkedHashMap<>();
        records.put(AnvilRegion.slotIndex(0, 0), TestFixtures.record(TestFixtures.chunkNbt(0, 0, new byte[]{1})));
        Path file = TestFixtures.writeRegion(tmp.resolve("r.0.0.mca"), records, true);
        byte[] tooBig = TestFixtures.record(TestFixtures.chunkNbt(0, 0, TestFixtures.incompressible(9000, 7)));
        assertThrows(AnvilRegion.InPlaceNotPossible.class,
                () -> AnvilRegion.replaceInPlace(file, Map.of(AnvilRegion.slotIndex(0, 0), tooBig)));
    }

    @Test void rewriteCanAddReplaceAndRemove() throws Exception {
        Map<Integer, byte[]> records = new LinkedHashMap<>();
        records.put(AnvilRegion.slotIndex(0, 0), TestFixtures.record(TestFixtures.chunkNbt(0, 0, new byte[]{1})));
        records.put(AnvilRegion.slotIndex(1, 1), TestFixtures.record(TestFixtures.chunkNbt(1, 1, new byte[]{2})));
        Path file = TestFixtures.writeRegion(tmp.resolve("r.0.0.mca"), records, true);

        byte[] added = TestFixtures.record(TestFixtures.chunkNbt(4, 4, new byte[9000]));
        byte[] replaced = TestFixtures.record(TestFixtures.chunkNbt(1, 1, new byte[]{5, 5}));
        AnvilRegion.rewrite(file, Map.of(AnvilRegion.slotIndex(4, 4), added, AnvilRegion.slotIndex(1, 1), replaced),
                java.util.Set.of(AnvilRegion.slotIndex(0, 0)));

        Map<Integer, AnvilRegion.Slot> slots = AnvilRegion.readSlots(file);
        assertEquals(2, slots.size());
        assertTrue(slots.containsKey(AnvilRegion.slotIndex(4, 4)));
        assertArrayEquals(replaced, slots.get(AnvilRegion.slotIndex(1, 1)).bytes());
        assertFalse(slots.containsKey(AnvilRegion.slotIndex(0, 0)));
    }

    @Test void rejectsTruncatedRecord() throws Exception {
        Map<Integer, byte[]> records = new LinkedHashMap<>();
        records.put(AnvilRegion.slotIndex(2, 2), TestFixtures.record(TestFixtures.chunkNbt(2, 2, new byte[50])));
        Path file = TestFixtures.writeRegion(tmp.resolve("r.0.0.mca"), records, false);
        byte[] data = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(data, data.length - 10));
        assertThrows(java.io.IOException.class, () -> AnvilRegion.readSlots(file));
    }
}
