package cn.qzgeek.gitbackup.storage;

import java.io.InputStream;
import java.util.List;

/**
 * 统一存储 API。
 *
 * <p>刻意只保留"文件系统级"的能力，不含任何具体存储的特性：没有分片上传、没有秒传、
 * 没有回收站、没有令牌刷新、没有 rename 语义。这些都属于适配器内部实现，上层备份引擎
 * 不应该也没有办法依赖它们。
 *
 * <p>路径约定：统一用正斜杠分隔、相对于适配器根目录、不带前导斜杠，例如
 * {@code minecraft/overworld/packs/2026-10/20261018-1400-a1b2.pk}。
 *
 * <p>实现约定（上层依赖这三条）：
 * <ul>
 *   <li>写入只用于"新对象"，不要求支持覆盖同名文件；</li>
 *   <li>列举结果不保证顺序，调用方自行排序；</li>
 *   <li>方法实现必须线程安全（备份与恢复可能并发调用）。</li>
 * </ul>
 */
public interface StorageAdapter extends AutoCloseable {

    /** 适配器类型名，与配置里的 type 对应，例如 local / 139 / ftp。 */
    String type();

    boolean exists(String path) throws AdapterException;

    /** 对象大小；不存在时返回 -1。 */
    long size(String path) throws AdapterException;

    /** 列出目录下的直接子项；目录不存在时返回空列表。 */
    List<StorageEntry> list(String path) throws AdapterException;

    /** 创建目录（含父目录）；已存在视为成功。 */
    void mkdirs(String path) throws AdapterException;

    /** 流式读取，调用方负责关闭。 */
    InputStream read(String path) throws AdapterException;

    /**
     * 流式写入。
     *
     * @param length 字节数；实现无法预知时传 -1（适配器需自行处理，例如分片或缓冲）
     */
    void write(String path, InputStream data, long length) throws AdapterException;

    /** 删除对象；目录按适配器自身语义处理（递归或失败）。 */
    void delete(String path) throws AdapterException;

    @Override
    default void close() throws Exception { }
}
