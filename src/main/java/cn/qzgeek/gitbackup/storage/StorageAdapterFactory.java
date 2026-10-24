package cn.qzgeek.gitbackup.storage;

import java.util.Map;

/**
 * 适配器工厂（SPI 入口）。
 *
 * <p>扩展 jar 内放 {@code META-INF/services/cn.qzgeek.gitbackup.storage.StorageAdapterFactory}
 * 声明实现类即可被 {@link AdapterRegistry} 发现。
 */
public interface StorageAdapterFactory {

    /** 类型名，配置里用 {@code storage.type} 指定。 */
    String type();

    /**
     * 创建适配器实例。
     *
     * @param config 该存储的配置键值（来自 config.yml 的 storage 段），适配器自行解释
     */
    StorageAdapter create(Map<String, String> config) throws Exception;
}
