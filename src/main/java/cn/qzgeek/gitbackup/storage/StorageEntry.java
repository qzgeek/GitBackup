package cn.qzgeek.gitbackup.storage;

/** 目录条目。只暴露各存储都能稳定提供的信息。 */
public record StorageEntry(String name, boolean directory, long size) {

    public StorageEntry {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("条目名不能为空");
    }
}
