package cn.qzgeek.gitbackup;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 恢复引擎：把历史提交里的区块对象还原回世界目录。
 *
 * <p>整个恢复被拆成"计划"和"执行"两步。计划阶段只读 Git 对象，不碰存档；
 * 执行阶段才写文件。计划会明确判定每个区块能否就地热恢复：
 * <ul>
 *   <li>目标版本中该区块存在，且新记录放得进原分配扇区 → 可就地热恢复（目录表不变，服务端缓存不失效）</li>
 *   <li>目标版本中该区块不存在（需要删除）、或记录变大、或槽位原本为空 → 必须重建 region 文件，只能在世界未加载时做</li>
 * </ul>
 * 这个判定是硬性的：宁可拒绝并要求下次启动应用，也不在服务端持有旧目录表的情况下做破坏性写入。
 */
final class RestoreEngine {
    /** 区块坐标（区块单位）。 */
    record Chunk(int x, int z) {
        static Chunk parse(String value) {
            String[] p = value.split(",");
            if (p.length != 2) throw new IllegalArgumentException("区块坐标格式应为 <x>,<z>: " + value);
            return new Chunk(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()));
        }
        int regionX() { return Math.floorDiv(x, 32); }
        int regionZ() { return Math.floorDiv(z, 32); }
        int slot() { return AnvilRegion.slotIndex(Math.floorMod(x, 32), Math.floorMod(z, 32)); }
        String regionFile() { return "r." + regionX() + "." + regionZ() + ".mca"; }
        @Override public String toString() { return x + "," + z; }
    }

    /** 一个待写入的区块记录（已按目标 region 文件与槽位归位）。 */
    record Write(Path file, int slot, byte[] bytes, Chunk chunk, String label) {}

    /** 一处必须重建 region 文件才能完成的改动。 */
    record ColdChange(Path file, Map<Integer, byte[]> writes, Set<Integer> removals, String reason) {}

    record Plan(WorldTarget world, RevCommit commit, List<Write> hotWrites, List<ColdChange> coldChanges,
                Map<String, byte[]> extras, List<String> notes, Set<Chunk> chunks) {
        boolean hotOnly() { return coldChanges.isEmpty(); }
        boolean empty() { return hotWrites.isEmpty() && coldChanges.isEmpty(); }
    }

    private final BackupStore store;

    RestoreEngine(BackupStore store) { this.store = store; }

    /**
     * 区块对象在仓库中的路径。
     *
     * <p>必须按区域分片：早期版本把所有区块平铺在同一目录，一个 13 GiB 存档会产生
     * 170 万条目的单个 tree 对象（40 MB+），JGit 读不动、Git 端也要每次重写整棵树。
     * 分片后每个目录约 800 个条目，tree 只有几十 KB，增量提交只动变化的区域。
     */
    static String chunkPath(String base, Chunk chunk) {
        return base + "chunks/r." + chunk.regionX() + "." + chunk.regionZ()
                + "/" + chunk.x() + "." + chunk.z() + ".bin";
    }

    /** 列出该世界在给定提交中存在的全部 Anvil 区域目录（动态发现，不写死 region/entities/poi）。 */
    Set<String> anvilDirs(RevCommit commit, WorldTarget world) throws IOException {
        String prefix = world.repoPath() + "/";
        Set<String> dirs = new TreeSet<>();
        try (var tw = new org.eclipse.jgit.treewalk.TreeWalk(store.repository())) {
            tw.setRecursive(true);
            tw.addTree(commit.getTree());
            while (tw.next()) {
                String path = tw.getPathString();
                if (!path.startsWith(prefix)) continue;
                String rest = path.substring(prefix.length());
                int marker = rest.indexOf("/chunks/");
                if (marker <= 0) continue;
                String dirName = rest.substring(0, marker);
                if (!dirName.contains("/")) dirs.add(dirName);
            }
        }
        return dirs;
    }

    Plan plan(RevCommit commit, WorldTarget world, Set<Chunk> chunks) throws Exception {
        List<Write> hot = new ArrayList<>();
        Map<Path, ColdChange> cold = new LinkedHashMap<>();
        Map<String, byte[]> extras = new TreeMap<>();
        List<String> notes = new ArrayList<>();
        Set<String> dirs = anvilDirs(commit, world);
        Map<Path, Map<Integer, AnvilRegion.Slot>> slotCache = new LinkedHashMap<>();

        for (Chunk chunk : chunks) {
            for (String dir : dirs) {
                String base = world.repoPath() + "/" + dir + "/";
                byte[] bytes = store.readBlob(commit, chunkPath(base, chunk));
                Path regionFile = world.root().resolve(dir).resolve(chunk.regionFile()).toAbsolutePath().normalize();
                Map<Integer, AnvilRegion.Slot> slots = slotCache.computeIfAbsent(regionFile, f -> {
                    try {
                        return Files.exists(f) ? AnvilRegion.readSlots(f) : Map.of();
                    } catch (IOException e) {
                        throw new IllegalStateException("无法读取 region 文件: " + f + " → " + e.getMessage(), e);
                    }
                });
                if (bytes == null) {
                    // 目标版本没有这个区块。只有"当前磁盘上存在"时才算需要删除的破坏性改动；
                    // 两边都没有（例如该区块本来就没有 entities/poi 数据）则视为无需处理，
                    // 否则几乎所有区块都会被迫走冷态重建。
                    if (slots.containsKey(chunk.slot())) {
                        cold.computeIfAbsent(regionFile,
                                        f -> new ColdChange(f, new LinkedHashMap<>(), new LinkedHashSet<>(), "目标版本不存在该区块，需要删除"))
                                .removals().add(chunk.slot());
                    }
                    continue;
                }
                AnvilRegion.Slot slot = slots.get(chunk.slot());
                if (slot == null) {
                    cold.computeIfAbsent(regionFile,
                                    f -> new ColdChange(f, new LinkedHashMap<>(), new LinkedHashSet<>(), "区域内原本没有该区块槽位"))
                            .writes().put(chunk.slot(), bytes);
                } else if ((bytes.length + AnvilRegion.SECTOR - 1) / AnvilRegion.SECTOR > slot.sectors()) {
                    cold.computeIfAbsent(regionFile,
                                    f -> new ColdChange(f, new LinkedHashMap<>(), new LinkedHashSet<>(), "记录大于原分配扇区数"))
                            .writes().put(chunk.slot(), bytes);
                } else {
                    hot.add(new Write(regionFile, chunk.slot(), bytes, chunk, world.key() + "/" + dir));
                }
                // 同一目录下该区块的外部数据（.mcc）
                byte[] mcc = store.readBlob(commit, base + "mcc/r." + chunk.regionX() + "." + chunk.regionZ() + "/" + chunk.x() + "." + chunk.z() + ".mcc");
                Path mccFile = world.root().resolve(dir).resolve("c." + chunk.x() + "." + chunk.z() + ".mcc");
                if (mcc != null) extras.put(mccFile.toAbsolutePath().normalize().toString(), mcc);
            }
        }
        if (dirs.isEmpty()) notes.add("该世界在目标提交中没有任何区块数据");
        return new Plan(world, commit, hot, new ArrayList<>(cold.values()), extras, notes, chunks);
    }

    /** 就地写入：调用前必须保证对应区块未加载。返回写入的文件数。 */
    int applyHot(Plan plan) throws IOException {
        Map<Path, Map<Integer, byte[]>> byFile = new LinkedHashMap<>();
        for (Write w : plan.hotWrites()) byFile.computeIfAbsent(w.file(), f -> new LinkedHashMap<>()).put(w.slot(), w.bytes());
        for (Map.Entry<Path, Map<Integer, byte[]>> e : byFile.entrySet())
            AnvilRegion.replaceInPlace(e.getKey(), e.getValue());
        writeExtras(plan);
        return byFile.size();
    }

    /** 冷态重建：只能在目标世界未加载（停服或加载前）时调用。 */
    int applyCold(Plan plan) throws IOException {
        for (ColdChange change : plan.coldChanges()) {
            Map<Integer, byte[]> writes = new LinkedHashMap<>(change.writes());
            for (Write w : plan.hotWrites())
                if (w.file().equals(change.file())) writes.put(w.slot(), w.bytes());
            AnvilRegion.rewrite(change.file(), writes, change.removals());
        }
        for (Write w : plan.hotWrites()) {
            boolean covered = plan.coldChanges().stream().anyMatch(c -> c.file().equals(w.file()));
            if (!covered) AnvilRegion.replaceInPlace(w.file(), Map.of(w.slot(), w.bytes()));
        }
        writeExtras(plan);
        return plan.coldChanges().size();
    }

    private void writeExtras(Plan plan) throws IOException {
        for (Map.Entry<String, byte[]> e : plan.extras().entrySet()) {
            Path target = Path.of(e.getKey());
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".gitbackup.tmp");
            Files.write(tmp, e.getValue());
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 该区块在给定提交中是否存在（用于列表展示与判定）。 */
    boolean exists(RevCommit commit, WorldTarget world, Chunk chunk) throws IOException {
        for (String dir : anvilDirs(commit, world)) {
            if (store.readBlob(commit, chunkPath(world.repoPath() + "/" + dir + "/", chunk)) != null)
                return true;
        }
        return false;
    }
}
