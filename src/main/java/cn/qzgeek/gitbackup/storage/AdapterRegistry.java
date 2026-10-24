package cn.qzgeek.gitbackup.storage;

import cn.qzgeek.gitbackup.storage.local.LocalDirAdapterFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * 适配器注册表。
 *
 * <p>内置适配器（local）直接注册；第三方适配器放在 {@code plugins/GitBackup/extensions/*.jar}，
 * 通过 {@link ServiceLoader} 发现。扩展目录里每个 jar 用
 * {@code META-INF/services/cn.qzgeek.gitbackup.storage.StorageAdapterFactory} 声明实现。
 *
 * <p>扩展加载失败只影响该扩展，不影响插件本体与已加载的其它适配器。
 */
public final class AdapterRegistry {

    private final Logger logger;
    private final Map<String, StorageAdapterFactory> factories = new TreeMap<>();
    private final List<URLClassLoader> loaders = new ArrayList<>();

    public AdapterRegistry(Logger logger) {
        this.logger = logger;
        factories.put("local", new LocalDirAdapterFactory());
    }

    /** 扫描扩展目录（不存在则忽略）。 */
    public void loadExtensions(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] jars = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) return;
        for (File jar : jars) {
            try {
                URLClassLoader loader = new URLClassLoader(
                        new URL[]{jar.toURI().toURL()}, AdapterRegistry.class.getClassLoader());
                int before = factories.size();
                for (StorageAdapterFactory f : ServiceLoader.load(StorageAdapterFactory.class, loader)) {
                    String type = f.type();
                    if (type == null || type.isBlank()) {
                        logger.warning("扩展 " + jar.getName() + " 提供了没有 type 的适配器，已忽略");
                        continue;
                    }
                    factories.put(type.toLowerCase(), f);
                }
                loaders.add(loader);
                int added = factories.size() - before;
                logger.info("已加载适配器扩展 " + jar.getName() + "（新增 " + Math.max(added, 0) + " 个）");
            } catch (Throwable t) {
                logger.severe("加载适配器扩展失败 " + jar.getName() + " → " + t);
            }
        }
    }

    /** 按类型创建适配器。 */
    public StorageAdapter create(String type, Map<String, String> config) throws Exception {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("未配置存储类型");
        StorageAdapterFactory f = factories.get(type.toLowerCase());
        if (f == null)
            throw new IllegalArgumentException("未知存储类型: " + type + "，已加载: " + factories.keySet());
        return f.create(config == null ? Map.of() : new LinkedHashMap<>(config));
    }

    public Collection<String> types() { return factories.keySet(); }

    public boolean has(String type) { return type != null && factories.containsKey(type.toLowerCase()); }

    public void close() {
        for (URLClassLoader l : loaders) {
            try { l.close(); } catch (Exception ignored) { }
        }
        loaders.clear();
    }
}
