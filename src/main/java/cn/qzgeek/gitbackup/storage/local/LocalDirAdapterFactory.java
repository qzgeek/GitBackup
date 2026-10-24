package cn.qzgeek.gitbackup.storage.local;

import cn.qzgeek.gitbackup.storage.StorageAdapter;
import cn.qzgeek.gitbackup.storage.StorageAdapterFactory;

import java.nio.file.Path;
import java.util.Map;

/** 内置适配器：local。配置项只有一个 root。 */
public final class LocalDirAdapterFactory implements StorageAdapterFactory {

    @Override public String type() { return "local"; }

    @Override public StorageAdapter create(Map<String, String> config) throws Exception {
        String root = config.getOrDefault("root", ".");
        return new LocalDirAdapter(Path.of(root));
    }
}
