package cn.qzgeek.gitbackup;

import java.nio.file.Path;

/**
 * 一个备份目标世界。
 *
 * <p>世界标识一律使用服务端真实数据：Bukkit 世界名、维度键（namespace:dimension）、实际目录。
 * 目录名不写死——`world_nether` 这类名字只出现在默认配置的初值里，实际值来自运行时读取。
 */
record WorldTarget(String name, String namespace, String dimension, Path root, boolean enabled) {

    String key() { return namespace + ":" + dimension; }

    String repoPath() { return "data/worlds/" + namespace + "/" + dimension; }

    static WorldTarget offline(String key, Path root) {
        int c = key.indexOf(':');
        String ns = c < 0 ? "minecraft" : key.substring(0, c);
        String dim = c < 0 ? key : key.substring(c + 1);
        return new WorldTarget(key, ns, dim, root, true);
    }
}
