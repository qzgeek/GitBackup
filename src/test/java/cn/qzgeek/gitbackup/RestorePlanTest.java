package cn.qzgeek.gitbackup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 恢复计划判定的边界行为。
 *
 * <p>这些用例锁定了一个真实踩到的坑：区块在 entities/poi 目录下本来就没有记录时，
 * 不能因为"目标提交里没有"就判定为"需要删除"，否则几乎所有区块都会被迫走冷态重建。
 */
class RestorePlanTest {
    @TempDir Path tmp;

    private WorldTarget target(Path dir) {
        return new WorldTarget("world", "minecraft", "overworld", dir, true);
    }

    @Test void chunkMissingOnBothSidesIsNotADestructiveChange() throws Exception {
        Path world = TestFixtures.world(tmp.resolve("world"), "overworld", new int[][]{{0, 0}});
        // 另建一个 entities 目录，但里面只有别的区块（不含 0,0）
        TestFixtures.writeRegion(world.resolve("entities").resolve("r.0.0.mca"),
                Map.of(AnvilRegion.slotIndex(5, 5), TestFixtures.record(TestFixtures.chunkNbt(5, 5, new byte[]{1}))),
                true);
        try (BackupStore store = new BackupStore(tmp.resolve("repo"), "main", "", null, null, null, null)) {
            SnapshotIndex index = SnapshotIndex.load(tmp.resolve("index.json"));
            SnapshotEngine engine = new SnapshotEngine(store, index, true);
            String commit = engine.backup(List.of(target(world)), "snapshot", Map.of(), null).commit();

            RestoreEngine engine2 = new RestoreEngine(store);
            var plan = engine2.plan(store.resolveCommit(commit), target(world),
                    Set.of(new RestoreEngine.Chunk(0, 0)));
            assertTrue(plan.hotOnly(),
                    "区块 0,0 在 entities/poi 本来就不存在，不应触发删除型冷态重建: " + plan.coldChanges());
            assertFalse(plan.hotWrites().isEmpty(), "region 区块应可就地写回");
        }
    }

    @Test void chunkPresentNowButAbsentInTargetIsAColdDeletion() throws Exception {
        Path world = TestFixtures.world(tmp.resolve("world"), "overworld", new int[][]{{0, 0}});
        try (BackupStore store = new BackupStore(tmp.resolve("repo"), "main", "", null, null, null, null)) {
            SnapshotIndex index = SnapshotIndex.load(tmp.resolve("index.json"));
            SnapshotEngine engine = new SnapshotEngine(store, index, true);
            String commit = engine.backup(List.of(target(world)), "only 0,0", Map.of(), null).commit();

            // 备份之后新增一个区块 1,0
            Path region = world.resolve("region").resolve("r.0.0.mca");
            Map<Integer, byte[]> merged = new LinkedHashMap<>();
            for (var e : AnvilRegion.readSlots(region).entrySet()) merged.put(e.getKey(), e.getValue().bytes());
            merged.put(AnvilRegion.slotIndex(1, 0), TestFixtures.record(TestFixtures.chunkNbt(1, 0, new byte[]{9})));
            AnvilRegion.rewrite(region, merged, Set.of());
            Files.writeString(world.resolve("level.dat"), "changed");

            var plan = new RestoreEngine(store).plan(store.resolveCommit(commit), target(world),
                    Set.of(new RestoreEngine.Chunk(1, 0)));
            assertFalse(plan.hotOnly(), "当前存在但目标版本不存在 → 必须冷态删除");
            assertTrue(plan.coldChanges().stream().anyMatch(c -> !c.removals().isEmpty()), "应包含删除操作");
        }
    }

    @Test void defaultExcludesCoverRealPaperLayout() throws Exception {
        Path world = tmp.resolve("world");
        Files.createDirectories(world.resolve("players/data"));
        Files.createDirectories(world.resolve("dimensions/minecraft/overworld/region"));
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("players/data/abc.dat"), "player");
        Files.writeString(world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"), "x");
        Files.writeString(world.resolve("level.dat"), "level");
        TestFixtures.writeRegion(world.resolve("region").resolve("r.0.0.mca"),
                Map.of(AnvilRegion.slotIndex(0, 0), TestFixtures.record(TestFixtures.chunkNbt(0, 0, new byte[]{1}))), true);

        try (BackupStore store = new BackupStore(tmp.resolve("repo"), "main", "", null, null, null, null)) {
            SnapshotIndex index = SnapshotIndex.load(tmp.resolve("index.json"));
            SnapshotEngine engine = new SnapshotEngine(store, index, true);
            String commit = engine.backup(List.of(target(world)), "excludes", Map.of(), null).commit();

            var paths = new java.util.ArrayList<String>();
            try (var tw = new org.eclipse.jgit.treewalk.TreeWalk(store.repository())) {
                tw.setRecursive(true);
                tw.addTree(store.resolveCommit(commit).getTree());
                while (tw.next()) paths.add(tw.getPathString());
            }
            String prefix = "data/worlds/minecraft/overworld/";
            assertTrue(paths.stream().noneMatch(p -> p.startsWith(prefix + "files/players/")), "players/ 必须被排除");
            assertTrue(paths.stream().noneMatch(p -> p.startsWith(prefix + "files/dimensions/")), "dimensions/ 必须被排除");
            assertTrue(paths.stream().anyMatch(p -> p.equals(prefix + "files/level.dat")), "level.dat 必须被备份");
            assertTrue(paths.stream().anyMatch(p -> p.startsWith(prefix + "region/chunks/r.")), "区块必须被备份");
        }
    }
}
