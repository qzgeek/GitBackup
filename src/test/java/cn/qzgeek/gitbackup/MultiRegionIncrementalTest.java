package cn.qzgeek.gitbackup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多区域文件场景下的增量正确性。
 *
 * <p>回归用例：曾经因为"前缀查询上界写错"导致未变更区域的父提交对象被误判为需要删除，
 * 备份会把其它区域整个删掉且不报错——只有多个 region 文件才能暴露该问题。
 */
class MultiRegionIncrementalTest {
    @TempDir Path tmp;

    private WorldTarget target(Path dir) {
        return new WorldTarget("world", "minecraft", "overworld", dir, true);
    }

    @Test void unchangedRegionSurvivesWhenAnotherRegionChanges() throws Exception {
        Path world = tmp.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("level.dat"), "level");

        // 两个 region 文件：r.0.0 含区块 (0,0)，r.1.0 含区块 (32,0)
        Map<Integer, byte[]> first = new LinkedHashMap<>();
        first.put(AnvilRegion.slotIndex(0, 0), TestFixtures.record(TestFixtures.chunkNbt(0, 0, new byte[]{1, 2, 3})));
        TestFixtures.writeRegion(world.resolve("region").resolve("r.0.0.mca"), first, false);

        Map<Integer, byte[]> second = new LinkedHashMap<>();
        second.put(AnvilRegion.slotIndex(0, 0), TestFixtures.record(TestFixtures.chunkNbt(32, 0, new byte[]{9, 9})));
        TestFixtures.writeRegion(world.resolve("region").resolve("r.1.0.mca"), second, false);

        try (BackupStore store = new BackupStore(tmp.resolve("repo"), "main", "", null, null, null, null)) {
            SnapshotIndex index = SnapshotIndex.load(tmp.resolve("index.json"));
            SnapshotEngine engine = new SnapshotEngine(store, index, true);
            String c1 = engine.backup(List.of(target(world)), "baseline", Map.of(), null).commit();
            assertNotNull(store.readBlob(c1, "data/worlds/minecraft/overworld/region/chunks/r.0.0/0.0.bin"));
            assertNotNull(store.readBlob(c1, "data/worlds/minecraft/overworld/region/chunks/r.1.0/32.0.bin"));

            // 只改 r.0.0 里的区块，r.1.0 完全不动
            byte[] changed = TestFixtures.record(TestFixtures.chunkNbt(0, 0, new byte[]{7, 7, 7}));
            byte[] regionBytes = Files.readAllBytes(world.resolve("region").resolve("r.0.0.mca"));
            assertTrue(changed.length <= regionBytes.length);
            AnvilRegion.replaceInPlace(world.resolve("region").resolve("r.0.0.mca"),
                    Map.of(AnvilRegion.slotIndex(0, 0), changed));

            var r2 = engine.backup(List.of(target(world)), "second", Map.of(), null);
            String c2 = r2.commit();
            assertNotNull(c2, "应当产生新提交");

            // 关键断言：未变更区域的对象必须仍然存在
            assertNotNull(store.readBlob(c2, "data/worlds/minecraft/overworld/region/chunks/r.1.0/32.0.bin"),
                    "未变更区域 r.1.0 的区块对象被误删（增量删除逻辑回归）");
            assertNotNull(store.readBlob(c2, "data/worlds/minecraft/overworld/region/chunks/r.0.0/0.0.bin"));
            assertNotNull(store.readBlob(c2, "data/worlds/minecraft/overworld/files/level.dat"));
        }
    }

    @Test void manifestStyleCommitKeepsAllData() throws Exception {
        Path world = tmp.resolve("world2");
        Files.createDirectories(world.resolve("region"));
        Map<Integer, byte[]> rec = new LinkedHashMap<>();
        rec.put(AnvilRegion.slotIndex(0, 0), TestFixtures.record(TestFixtures.chunkNbt(0, 0, new byte[]{4})));
        TestFixtures.writeRegion(world.resolve("region").resolve("r.0.0.mca"), rec, false);

        try (BackupStore store = new BackupStore(tmp.resolve("repo2"), "main", "", null, null, null, null)) {
            SnapshotIndex index = SnapshotIndex.load(tmp.resolve("index2.json"));
            SnapshotEngine engine = new SnapshotEngine(store, index, true);
            engine.backup(List.of(target(world)), "baseline", Map.of(), null).commit();

            // 模拟 commitManifest：以父提交为基线 + 只增一个根文件
            BackupStore.Tree t = new BackupStore.Tree();
            t.putAll(store.parentBlobs());
            t.put(BackupStore.VERSION_FILE, store.insertBlob(BackupStore.FORMAT.getBytes()));
            var rev = store.commit(t, Set.of(), "bookkeeping", Map.of(BackupStore.INFO_FILE, "{\"format\":1}"));
            assertNotNull(rev);
            assertNotNull(store.readBlob(rev, "data/worlds/minecraft/overworld/region/chunks/r.0.0/0.0.bin"),
                    "账本提交不得丢失区块对象");
        }
    }
}
