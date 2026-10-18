package cn.qzgeek.gitbackup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** backup_info.json 的内存模型。该文件随每次备份一起提交，是仓库内的"备份账本"。 */
final class BackupManifest {
    static final int FORMAT = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    int format = FORMAT;
    String tool = "GitBackup";
    String toolVersion = "";
    String updatedAt = "";
    List<SnapshotEntry> snapshots = new ArrayList<>();
    List<RollbackEntry> rollbacks = new ArrayList<>();
    List<PendingRestore> pending = new ArrayList<>();

    static BackupManifest parse(byte[] data) {
        if (data == null || data.length == 0) return new BackupManifest();
        try {
            BackupManifest m = GSON.fromJson(new String(data, java.nio.charset.StandardCharsets.UTF_8), BackupManifest.class);
            if (m == null) return new BackupManifest();
            if (m.snapshots == null) m.snapshots = new ArrayList<>();
            if (m.rollbacks == null) m.rollbacks = new ArrayList<>();
            if (m.pending == null) m.pending = new ArrayList<>();
            if (m.format != FORMAT) throw new IllegalStateException("不支持的 backup_info 格式: " + m.format);
            return m;
        } catch (RuntimeException e) {
            throw new IllegalStateException("backup_info.json 无法解析: " + e.getMessage(), e);
        }
    }

    String toJson() {
        updatedAt = Instant.now().toString();
        return GSON.toJson(this);
    }

    void addSnapshot(SnapshotEntry entry) {
        snapshots.add(0, entry);
        trim(snapshots, 500);
    }

    void addRollback(RollbackEntry entry) {
        rollbacks.add(0, entry);
        trim(rollbacks, 500);
    }

    RollbackEntry findRollback(String id) {
        for (RollbackEntry r : rollbacks) if (r.id.equals(id)) return r;
        return null;
    }

    private static <T> void trim(List<T> list, int max) {
        while (list.size() > max) list.remove(list.size() - 1);
    }

    static final class SnapshotEntry {
        String id;
        String commit;
        String time;
        String trigger;
        int objects;
        long bytes;
        Set<String> worlds = new LinkedHashSet<>();
        List<String> degraded = new ArrayList<>();
    }

    static final class RollbackEntry {
        String id;
        String restoreId;
        String world;
        String commit;      // 恢复目标提交
        String preCommit;   // 恢复前快照提交（回滚时从这里取数据）
        String time;
        int chunks;
        boolean applied;
        boolean rolledBack;
        String note;
    }

    static final class PendingRestore {
        String id;
        String world;
        String worldKey;
        String worldRoot;
        String commit;
        String time;
        String requestedBy;
        List<String> kinds = new ArrayList<>();
        List<String> chunks = new ArrayList<>();
    }
}
