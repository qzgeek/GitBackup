package cn.qzgeek.gitbackup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 本地增量索引：只记录"源文件 → (大小, 修改时间)"。
 *
 * <p>早期版本在这里按区块记录每个对象 id，结果在一个真实 13 GiB 存档上产生约 500 万条目、
 * 每次备份都要序列化上百 MB JSON，成为整个流程的瓶颈。对象 id 完全可以从未变更区域的父提交
 * 树里直接继承，因此这里只保留判断"是否需要重新读取"的最小信息。
 */
final class SnapshotIndex {
    private static final Gson GSON = new GsonBuilder().create();
    private final Path file;
    private Map<String, Stamp> stamps = new TreeMap<>();
    private boolean dirty;

    SnapshotIndex(Path file) { this.file = file; }

    static SnapshotIndex load(Path file) {
        SnapshotIndex idx = new SnapshotIndex(file);
        if (Files.exists(file)) {
            try {
                IndexData data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), IndexData.class);
                if (data != null && data.format == 2 && data.files != null) idx.stamps = new TreeMap<>(data.files);
            } catch (Exception e) {
                // 索引损坏只影响性能：丢弃后下次备份会做全量校验。
                idx.stamps = new TreeMap<>();
            }
        }
        return idx;
    }

    /** 源文件自上次备份以来是否未变更。 */
    boolean unchanged(String sourcePath, long size, long mtimeNanos) {
        Stamp s = stamps.get(sourcePath);
        return s != null && s.size == size && s.mtime == mtimeNanos;
    }

    void stamp(String sourcePath, long size, long mtimeNanos) {
        Stamp previous = stamps.get(sourcePath);
        if (previous != null && previous.size == size && previous.mtime == mtimeNanos) return;
        stamps.put(sourcePath, new Stamp(size, mtimeNanos));
        dirty = true;
    }

    void retainOnly(java.util.Set<String> keep) {
        if (stamps.keySet().retainAll(keep)) dirty = true;
    }

    void save() {
        if (!dirty) return;
        try {
            Files.createDirectories(file.getParent());
            IndexData data = new IndexData();
            data.files = new HashMap<>(stamps);
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
            dirty = false;
        } catch (IOException e) {
            throw new IllegalStateException("无法写入备份索引: " + e.getMessage(), e);
        }
    }

    private static final class IndexData {
        int format = 2;
        Map<String, Stamp> files = new HashMap<>();
    }

    private static final class Stamp {
        long size;
        long mtime;

        Stamp() {}

        Stamp(long size, long mtime) {
            this.size = size;
            this.mtime = mtime;
        }
    }
}
