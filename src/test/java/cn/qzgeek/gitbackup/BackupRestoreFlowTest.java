package cn.qzgeek.gitbackup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端到端引擎测试：真实 Git 对象库 + 真实 Anvil 文件，不依赖服务端。 */
class BackupRestoreFlowTest {
    @TempDir Path tmp;

    private BackupStore openStore() throws Exception {
        return new BackupStore(tmp.resolve("repo"), "main", "", null, null, null, null);
    }

    private WorldTarget target(Path worldDir) {
        return new WorldTarget("world", "minecraft", "overworld", worldDir, true);
    }

    @Test void backupCommitRestoreRoundTrip() throws Exception {
        Path worldDir = TestFixtures.world(tmp.resolve("world"), "overworld",
                new int[][]{{0, 0}, {1, 0}});
        try (BackupStore store = openStore()) {
            SnapshotIndex index = SnapshotIndex.load(tmp.resolve("index.json"));
            SnapshotEngine engine = new SnapshotEngine(store, index, true);
            var r1 = engine.backup(List.of(target(worldDir)), "backup #1", Map.of(), null);
            assertFalse(r1.noChanges());
            String commit1 = r1.commit();
            assertNotNull(commit1);

            // 树结构符合约定
            assertNotNull(store.readBlob(commit1, "data/worlds/minecraft/overworld/region/chunks/r.0.0/0.0.bin"));
            assertNotNull(store.readBlob(commit1, "data/worlds/minecraft/overworld/region/chunks/r.0.0/1.0.bin"));
            assertNotNull(store.readBlob(commit1, "data/worlds/minecraft/overworld/files/level.dat"));
            assertNotNull(store.readBlob(commit1, "version.txt"));
            byte[] originalChunk = store.readBlob(commit1, "data/worlds/minecraft/overworld/region/chunks/r.0.0/0.0.bin");

            // 无改动：索引命中，不产生新提交
            var r2 = engine.backup(List.of(target(worldDir)), "backup #2", Map.of(), null);
            assertTrue(r2.noChanges(), "未变更时不应产生新提交");

            // 只改一个区块：新提交里另一区块 blob 必须完全复用
            Path region = worldDir.resolve("region").resolve("r.0.0.mca");
            Map<Integer, AnvilRegion.Slot> slots = AnvilRegion.readSlots(region);
            byte[] changed = TestFixtures.record(TestFixtures.chunkNbt(0, 0, "changed-payload!".getBytes()));
            assertTrue(changed.length <= slots.get(AnvilRegion.slotIndex(0, 0)).sectors() * AnvilRegion.SECTOR);
            AnvilRegion.replaceInPlace(region, Map.of(AnvilRegion.slotIndex(0, 0), changed));
            Files.writeString(worldDir.resolve("level.dat"), "level-data-changed");

            var r3 = engine.backup(List.of(target(worldDir)), "backup #3", Map.of(), null);
            assertFalse(r3.noChanges());
            String commit3 = r3.commit();
            assertEquals(2, r3.changed(), "应只有 1 个区块对象 + level.dat 发生变化（实际 " + r3.changed() + "）");
            assertArrayEquals(store.readBlob(commit1, "data/worlds/minecraft/overworld/region/chunks/r.0.0/1.0.bin"),
                    store.readBlob(commit3, "data/worlds/minecraft/overworld/region/chunks/r.0.0/1.0.bin"));
            assertFalse(java.util.Arrays.equals(
                    store.readBlob(commit1, "data/worlds/minecraft/overworld/region/chunks/r.0.0/0.0.bin"),
                    store.readBlob(commit3, "data/worlds/minecraft/overworld/region/chunks/r.0.0/0.0.bin")),
                    "被修改的区块在新提交中必须是不同内容");

            // 恢复到 commit1：chunk 0,0 应回到原始字节
            RestoreEngine restore = new RestoreEngine(store);
            Set<RestoreEngine.Chunk> chunks = new LinkedHashSet<>(Set.of(new RestoreEngine.Chunk(0, 0)));
            var plan = restore.plan(store.resolveCommit(commit1), target(worldDir), chunks);
            assertTrue(plan.hotOnly(), "同尺寸替换应可热恢复，实际: " + plan.coldChanges());
            restore.applyHot(plan);
            Map<Integer, AnvilRegion.Slot> after = AnvilRegion.readSlots(region);
            assertArrayEquals(originalChunk, after.get(AnvilRegion.slotIndex(0, 0)).bytes());
            // 未选中的区块必须原样保留
            assertArrayEquals(store.readBlob(commit3, "data/worlds/minecraft/overworld/region/chunks/r.0.0/1.0.bin"),
                    after.get(AnvilRegion.slotIndex(1, 0)).bytes());
        }
    }

    @Test void restoringChunkThatDidNotExistRequiresColdRewrite() throws Exception {
        Path worldDir = TestFixtures.world(tmp.resolve("world"), "overworld", new int[][]{{0, 0}});
        try (BackupStore store = openStore()) {
            SnapshotIndex index = SnapshotIndex.load(tmp.resolve("index.json"));
            SnapshotEngine engine = new SnapshotEngine(store, index, true);
            String commit1 = engine.backup(List.of(target(worldDir)), "only 0,0", Map.of(), null).commit();

            // 后来新增区块 2,0
            Path region = worldDir.resolve("region").resolve("r.0.0.mca");
            Map<Integer, byte[]> add = new LinkedHashMap<>(Map.of(
                    AnvilRegion.slotIndex(2, 0), TestFixtures.record(TestFixtures.chunkNbt(2, 0, new byte[]{42}))));
            var existing = AnvilRegion.readSlots(region);
            for (var e : existing.entrySet()) add.putIfAbsent(e.getKey(), e.getValue().bytes());
            AnvilRegion.rewrite(region, add, Set.of());

            RestoreEngine restore = new RestoreEngine(store);
            var plan = restore.plan(store.resolveCommit(commit1), target(worldDir),
                    Set.of(new RestoreEngine.Chunk(2, 0)));
            assertFalse(plan.hotOnly(), "目标版本不含该区块，必须走冷态重建");
            assertFalse(plan.coldChanges().isEmpty());
            restore.applyCold(plan);
            assertFalse(AnvilRegion.readSlots(region).containsKey(AnvilRegion.slotIndex(2, 0)),
                    "冷态重建后该区块应被删除");
            assertTrue(AnvilRegion.readSlots(region).containsKey(AnvilRegion.slotIndex(0, 0)),
                    "未涉及的区块不能被误删");
        }
    }

    @Test void growingRecordFallsBackToColdPath() throws Exception {
        Path worldDir = TestFixtures.world(tmp.resolve("world"), "overworld", new int[][]{{0, 0}});
        try (BackupStore store = openStore()) {
            SnapshotIndex index = SnapshotIndex.load(tmp.resolve("index.json"));
            SnapshotEngine engine = new SnapshotEngine(store, index, true);
            String c1 = engine.backup(List.of(target(worldDir)), "small", Map.of(), null).commit();

            Path region = worldDir.resolve("region").resolve("r.0.0.mca");
            // 让当前磁盘上的记录变得很大（10 扇区左右）
            byte[] big = TestFixtures.record(TestFixtures.chunkNbt(0, 0, TestFixtures.incompressible(40000, 11)));
            AnvilRegion.rewrite(region, Map.of(AnvilRegion.slotIndex(0, 0), big), Set.of());
            int sectorsNow = AnvilRegion.readSlots(region).get(AnvilRegion.slotIndex(0, 0)).sectors();
            assertTrue(sectorsNow >= 9, "测试前提：当前记录应占多个扇区，实际 " + sectorsNow);

            // 历史版本记录更小 → 原分配扇区足够 → 可热恢复
            var plan = new RestoreEngine(store).plan(store.resolveCommit(c1), target(worldDir),
                    Set.of(new RestoreEngine.Chunk(0, 0)));
            assertTrue(plan.hotOnly(), "历史记录更小，应可原地写回: " + plan.coldChanges());
            new RestoreEngine(store).applyHot(plan);
            assertArrayEquals(store.readBlob(c1, "data/worlds/minecraft/overworld/region/chunks/r.0.0/0.0.bin"),
                    AnvilRegion.readSlots(region).get(AnvilRegion.slotIndex(0, 0)).bytes());

            // 再制造一个"大"的历史提交，然后让磁盘回到小记录 → 此时恢复必须走冷态重建
            AnvilRegion.rewrite(region, Map.of(AnvilRegion.slotIndex(0, 0), big), Set.of());
            String bigCommit = engine.backup(List.of(target(worldDir)), "big", Map.of(), null).commit();
            AnvilRegion.rewrite(region, Map.of(AnvilRegion.slotIndex(0, 0),
                    TestFixtures.record(TestFixtures.chunkNbt(0, 0, new byte[]{1}))), Set.of());
            var plan2 = new RestoreEngine(store).plan(store.resolveCommit(bigCommit), target(worldDir),
                    Set.of(new RestoreEngine.Chunk(0, 0)));
            assertFalse(plan2.hotOnly(), "历史记录大于当前分配扇区时必须重建 region 文件");
        }
    }

    @Test void manifestPersistsRollbackRecords() throws Exception {
        BackupManifest manifest = new BackupManifest();
        BackupManifest.RollbackEntry e = new BackupManifest.RollbackEntry();
        e.id = "rb-1";
        e.world = "minecraft:overworld";
        e.commit = "a".repeat(40);
        e.preCommit = "b".repeat(40);
        e.time = java.time.Instant.now().toString();
        e.chunks = 3;
        e.applied = true;
        e.rolledBack = false;
        e.note = "hot|0,0;1,0;2,0";
        manifest.addRollback(e);

        BackupManifest parsed = BackupManifest.parse(manifest.toJson().getBytes());
        assertEquals(1, parsed.rollbacks.size());
        assertEquals("rb-1", parsed.findRollback("rb-1").id);
        assertNull(parsed.findRollback("nope"));
        assertEquals(BackupManifest.FORMAT, parsed.format);
    }

    @Test void commitTreeIsIncrementalNotRebuilt() throws Exception {
        Path worldDir = TestFixtures.world(tmp.resolve("world"), "overworld", new int[][]{{0, 0}});
        try (BackupStore store = openStore()) {
            SnapshotIndex index = SnapshotIndex.load(tmp.resolve("index.json"));
            SnapshotEngine engine = new SnapshotEngine(store, index, true);
            String c1 = engine.backup(List.of(target(worldDir)), "one", Map.of(), null).commit();

            // 模拟账本变更：以父提交为基线 + 新增一个根文件，数据树必须完整保留
            BackupStore.Tree t = new BackupStore.Tree();
            t.putAll(store.parentBlobs());
            t.put("version.txt", store.insertBlob(BackupStore.FORMAT.getBytes()));
            var rev = store.commit(t, Set.of(), "bookkeeping", Map.of("backup_info.json", "{\"format\":1}"));
            assertNotNull(rev);
            assertNotNull(store.readBlob(rev, "data/worlds/minecraft/overworld/region/chunks/r.0.0/0.0.bin"),
                    "增量提交必须保留父提交中的全部对象");
            assertNotNull(store.readBlob(rev, "backup_info.json"));
            assertNotNull(store.readBlob(c1, "data/worlds/minecraft/overworld/region/chunks/r.0.0/0.0.bin"));
        }
    }
}
