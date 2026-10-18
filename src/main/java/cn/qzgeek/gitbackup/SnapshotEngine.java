package cn.qzgeek.gitbackup;

import org.eclipse.jgit.lib.ObjectId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 快照引擎：把世界目录转成"区块对象 + 原样文件对象"的提交树。
 *
 * <p>普适性：不写死 region/entities/poi。任何目录下只要出现 {@code r.<x>.<z>.mca}，
 * 该目录就按 Anvil 区域目录处理（自定义维度、插件额外生成的区域目录都会被正确拆分）；
 * 其余文件按相对路径原样保存，因此额外存档分片不会漏。
 *
 * <p>增量策略（实测 13 GiB 真实存档后重写）：
 * <ol>
 *   <li>以父提交的 (路径 → 对象) 映射为基线，未变更区域直接继承，不读盘；</li>
 *   <li>只有 (大小, 修改时间) 变化的区域文件才重新解析；</li>
 *   <li>变更区域里只把内容真正不同的区块写入对象库。</li>
 * </ol>
 */
final class SnapshotEngine {
    /**
     * 默认排除的路径前缀，依据实际 Lophine 26.1.2 存档结构：
     * players/（新版玩家数据）、playerdata|advancements|stats（旧版）、
     * fakeplayerdata|resume_fakeplayerdata*（假人玩家状态）、
     * dimensions/（每个维度是独立世界，会被单独作为备份目标，整目录备份会重复）。
     */
    static final List<String> DEFAULT_EXCLUDES = List.of(
            "players", "playerdata", "advancements", "stats",
            "fakeplayerdata", "resume_fakeplayerdata", "dimensions");
    static final Set<String> SKIP_NAMES = Set.of("session.lock", ".DS_Store", "Thumbs.db");
    static final String IGNORE_SUFFIX = ".gitbackup.tmp";
    static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    static final Pattern MCC_NAME = Pattern.compile("c\\.(-?\\d+)\\.(-?\\d+)\\.mcc");

    private final BackupStore store;
    private final SnapshotIndex index;
    private final boolean validate;
    private final List<String> excludes;

    SnapshotEngine(BackupStore store, SnapshotIndex index, boolean validate) {
        this(store, index, validate, DEFAULT_EXCLUDES);
    }

    SnapshotEngine(BackupStore store, SnapshotIndex index, boolean validate, List<String> excludes) {
        this.store = store;
        this.index = index;
        this.validate = validate;
        this.excludes = excludes == null || excludes.isEmpty() ? DEFAULT_EXCLUDES : List.copyOf(excludes);
    }

    record Result(String commit, int objects, int reused, int changed, long bytes, int worlds,
                  List<String> degraded, boolean noChanges) {}

    Result backup(List<WorldTarget> worlds, String message, Map<String, String> extraRootFiles,
                  Progress progress) throws Exception {
        BackupStore.Tree tree = new BackupStore.Tree();
        Map<String, ObjectId> base = store.parentBlobs();
        tree.putAll(base);
        Counter counter = new Counter();
        List<String> degraded = new ArrayList<>();
        Set<String> liveSources = new HashSet<>();
        Set<String> removals = new LinkedHashSet<>();

        tree.put(BackupStore.VERSION_FILE, store.insertBlob(BackupStore.FORMAT.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        for (WorldTarget world : worlds) {
            Path root = world.root();
            if (!Files.isDirectory(root)) {
                degraded.add(world.key() + ": 世界目录不存在 " + root);
                continue;
            }
            scanWorld(world, root, tree, base, counter, degraded, liveSources, removals, progress);
        }
        index.retainOnly(liveSources);
        index.save();

        var rev = tree.isEmpty() ? null : store.commit(tree, removals, message, extraRootFiles);
        return new Result(rev == null ? null : rev.name(), tree.size(), counter.reused, counter.changed,
                counter.bytes, worlds.size(), degraded, rev == null);
    }

    private void scanWorld(WorldTarget world, Path root, BackupStore.Tree tree, Map<String, ObjectId> base,
                           Counter counter, List<String> degraded, Set<String> liveSources,
                           Set<String> removals, Progress progress) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        Map<Path, Boolean> anvilDirs = new HashMap<>();
        Set<String> producedPaths = new HashSet<>();
        String worldPrefix = world.repoPath() + "/";

        for (Path file : files) {
            Path relative = root.relativize(file);
            if (isExcluded(relative)) continue;
            String fileName = file.getFileName().toString();
            if (SKIP_NAMES.contains(fileName) || fileName.endsWith(IGNORE_SUFFIX)) continue;

            Matcher region = REGION_NAME.matcher(fileName);
            Matcher mcc = MCC_NAME.matcher(fileName);
            Path dir = file.getParent();
            boolean isAnvilDir = anvilDirs.computeIfAbsent(dir, d -> {
                try (Stream<Path> s = Files.list(d)) {
                    return s.anyMatch(p -> REGION_NAME.matcher(p.getFileName().toString()).matches());
                } catch (IOException e) {
                    return false;
                }
            });

            if (isAnvilDir && region.matches()) {
                backupRegion(world, file, relative, region, tree, counter, degraded, liveSources, removals, producedPaths);
            } else if (isAnvilDir && mcc.matches()) {
                continue; // .mcc 随所属区域一起处理
            } else {
                backupRawFile(world, file, relative, tree, counter, liveSources, producedPaths);
            }
            if (progress != null) progress.tick(fileName);
        }

        // 该世界在父提交里有、但本次已不存在的对象，必须从新树里删除
        for (String path : base.keySet()) {
            if (path.startsWith(worldPrefix) && !producedPaths.contains(path)) removals.add(path);
        }
    }

    private void backupRegion(WorldTarget world, Path file, Path relative, Matcher region, BackupStore.Tree tree,
                              Counter counter, List<String> degraded, Set<String> liveSources,
                              Set<String> removals, Set<String> producedPaths) throws IOException {
        String sourceId = world.key() + "|" + relative.toString().replace('\\', '/');
        liveSources.add(sourceId);
        String relDir = relative.getParent() == null ? "" : relative.getParent().toString().replace('\\', '/');
        String objectBase = world.repoPath() + "/" + (relDir.isEmpty() ? "" : relDir + "/");
        int rx = Integer.parseInt(region.group(1));
        int rz = Integer.parseInt(region.group(2));

        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException e) {
            degraded.add(relative + ": 无法读取属性 " + e.getMessage());
            return;
        }
        long size = attrs.size();
        long mtime = attrs.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS);

        // 只针对"当前这个 region 文件"对应的对象前缀做继承/清理。
        // 用目录级前缀会把同目录下其它 region 的对象一并卷进来（曾导致其它区域被误删）。
        String chunkPrefix = objectBase + "chunks/r." + cx0(rx, rz) + "/";
        String mccPrefix = objectBase + "mcc/r." + cx0(rx, rz) + "/";

        if (index.unchanged(sourceId, size, mtime)) {
            // 未变更：继承父提交里这个 region 的对象，不读盘
            for (String path : tree.pathsWithPrefix(chunkPrefix)) {
                producedPaths.add(path);
                counter.reused++;
            }
            for (String path : tree.pathsWithPrefix(mccPrefix)) {
                producedPaths.add(path);
                counter.reused++;
            }
            return;
        }

        Map<Integer, AnvilRegion.Slot> slots;
        try {
            slots = AnvilRegion.readSlots(file);
        } catch (IOException e) {
            degraded.add(relative + ": 区域文件读取失败 " + e.getMessage());
            return;
        }
        int changedHere = 0;
        for (Map.Entry<Integer, AnvilRegion.Slot> entry : slots.entrySet()) {
            AnvilRegion.Slot slot = entry.getValue();
            int cx = rx * 32 + slot.chunkXOffset();
            int cz = rz * 32 + slot.chunkZOffset();
            String path = objectBase + "chunks/r." + Math.floorDiv(cx, 32) + "." + Math.floorDiv(cz, 32)
                    + "/" + cx + "." + cz + ".bin";
            byte[] bytes = slot.bytes();
            ObjectId id = BackupStore.blobId(bytes);
            ObjectId existing = tree.get(path);
            if (existing != null && existing.equals(id)) {
                counter.reused++;
            } else {
                if (validate && bytes.length < 6) {
                    // 实测 174 万个区块里有 6 条记录连长度前缀都填不满（都在 entities/poi）。
                    // 无法校验，但也不该丢弃：备份工具宁可原样保存并明确记录，也不替用户做数据取舍。
                    degraded.add(relative + " 区块 " + cx + "," + cz + ": 记录过短无法校验，已原样保存");
                } else if (validate) {
                    try {
                        ChunkNbt.Header header = ChunkNbt.parse(bytes);
                        // poi 等类型不携带坐标：此时只能靠"可解压 + NBT 结构完整"判定完整性
                        if (header.x() != null && (header.x() != cx || header.z() != cz)) {
                            degraded.add(relative + " 区块 " + cx + "," + cz + ": 坐标不一致（疑似读写竞争），已跳过本次更新");
                            producedPaths.add(path); // 保留父提交里的旧版本，不能被当作"已删除"
                            continue;
                        }
                    } catch (ChunkNbt.Unsupported unsupported) {
                        // 服务端自定义压缩：无法自校验，按原始字节保存但不阻断备份
                    } catch (IOException invalid) {
                        degraded.add(relative + " 区块 " + cx + "," + cz + ": 记录校验失败（" + invalid.getMessage() + "），已跳过本次更新");
                        producedPaths.add(path); // 同上：保留旧版本
                        continue;
                    }
                }
                store.insertBlob(bytes);
                counter.changed++;
                counter.bytes += bytes.length;
                changedHere++;
                tree.put(path, id);
            }
            producedPaths.add(path);
        }

        // 同目录下的 c.<x>.<z>.mcc（部分服务端把区块外部数据单独存放）
        try (Stream<Path> siblings = Files.list(file.getParent())) {
            for (Path mccFile : siblings.toList()) {
                Matcher m = MCC_NAME.matcher(mccFile.getFileName().toString());
                if (!m.matches()) continue;
                byte[] bytes = Files.readAllBytes(mccFile);
                String path = objectBase + "mcc/r." + Math.floorDiv(Integer.parseInt(m.group(1)), 32)
                        + "." + Math.floorDiv(Integer.parseInt(m.group(2)), 32)
                        + "/" + m.group(1) + "." + m.group(2) + ".mcc";
                ObjectId id = BackupStore.blobId(bytes);
                ObjectId existing = tree.get(path);
                if (existing == null || !existing.equals(id)) {
                    store.insertBlob(bytes);
                    counter.changed++;
                    counter.bytes += bytes.length;
                } else {
                    counter.reused++;
                }
                tree.put(path, id);
                producedPaths.add(path);
            }
        }

        index.stamp(sourceId, size, mtime);
        // 本 region 在父提交里有、但本次解析已不存在的对象需要删除（只限本 region 的前缀）
        for (String path : tree.pathsWithPrefix(chunkPrefix)) {
            if (!producedPaths.contains(path) && store.parentBlobs().containsKey(path)) removals.add(path);
        }
        for (String path : tree.pathsWithPrefix(mccPrefix)) {
            if (!producedPaths.contains(path) && store.parentBlobs().containsKey(path)) removals.add(path);
        }
        if (changedHere == 0) {
            // 仅属性的变化（例如被 touch）不会产生新对象
        }
    }

    /** region 目录名 r.<rx>.<rz> 的坐标部分。 */
    private static String cx0(int rx, int rz) { return rx + "." + rz; }

    private void backupRawFile(WorldTarget world, Path file, Path relative, BackupStore.Tree tree,
                               Counter counter, Set<String> liveSources, Set<String> producedPaths) throws IOException {
        String rel = relative.toString().replace('\\', '/');
        String sourceId = world.key() + "|" + rel;
        liveSources.add(sourceId);
        String path = world.repoPath() + "/files/" + rel;
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException e) {
            return;
        }
        long size = attrs.size();
        long mtime = attrs.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS);
        producedPaths.add(path);
        if (index.unchanged(sourceId, size, mtime) && tree.contains(path)) {
            counter.reused++;
            return;
        }
        if (size > AnvilRegion.MAX_RECORD_BYTES) {
            throw new IOException("文件过大，拒绝备份: " + rel + " (" + size + " 字节)");
        }
        byte[] bytes = Files.readAllBytes(file);
        ObjectId id = BackupStore.blobId(bytes);
        ObjectId existing = tree.get(path);
        if (existing != null && existing.equals(id)) {
            counter.reused++;
        } else {
            store.insertBlob(bytes);
            counter.changed++;
            counter.bytes += bytes.length;
        }
        tree.put(path, id);
        index.stamp(sourceId, size, mtime);
    }

    /** 相对路径是否命中排除前缀（按路径段匹配，避免 players 误伤 players_old）。 */
    private boolean isExcluded(Path relative) {
        if (relative.getNameCount() == 0) return false;
        String first = relative.getName(0).toString();
        String full = relative.toString().replace('\\', '/');
        for (String prefix : excludes) {
            String normalized = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
            if (normalized.isEmpty()) continue;
            if (normalized.indexOf('/') < 0) {
                if (first.equals(normalized) || first.startsWith(normalized + ".") || first.startsWith(normalized + "_"))
                    return true;
            } else if (full.equals(normalized) || full.startsWith(normalized + "/")) {
                return true;
            }
        }
        return false;
    }

    private static final class Counter {
        int reused;
        int changed;
        long bytes;
    }

    interface Progress { void tick(String item); }

    static List<WorldTarget> sorted(List<WorldTarget> worlds) {
        List<WorldTarget> copy = new ArrayList<>(worlds);
        copy.sort(Comparator.comparing(WorldTarget::key));
        return copy;
    }
}
