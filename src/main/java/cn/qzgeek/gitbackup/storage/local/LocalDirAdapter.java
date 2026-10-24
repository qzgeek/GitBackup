package cn.qzgeek.gitbackup.storage.local;

import cn.qzgeek.gitbackup.storage.AdapterException;
import cn.qzgeek.gitbackup.storage.StorageAdapter;
import cn.qzgeek.gitbackup.storage.StorageEntry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地目录适配器。
 *
 * <p>用途有两个：一是在没有云端可用时做纯本地备份；二是作为统一 API 的"参照实现"，
 * 让上层（打包、快照、恢复）先在本地跑通并回归，再换云端适配器验证同样的代码路径。
 */
public final class LocalDirAdapter implements StorageAdapter {

    private final Path root;

    public LocalDirAdapter(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    @Override public String type() { return "local"; }

    @Override public boolean exists(String path) {
        return Files.exists(resolve(path), LinkOption.NOFOLLOW_LINKS);
    }

    @Override public long size(String path) throws AdapterException {
        Path p = resolve(path);
        try {
            return Files.isRegularFile(p) ? Files.size(p) : -1;
        } catch (IOException e) {
            throw new AdapterException("读取大小失败: " + path, e);
        }
    }

    @Override public List<StorageEntry> list(String path) throws AdapterException {
        Path dir = resolve(path);
        if (!Files.isDirectory(dir)) return List.of();
        List<StorageEntry> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                boolean isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
                long size = isDir ? 0 : Files.size(p);
                out.add(new StorageEntry(p.getFileName().toString(), isDir, size));
            }
        } catch (IOException e) {
            throw new AdapterException("列举目录失败: " + path, e);
        }
        out.sort(Comparator.comparing(StorageEntry::name));
        return out;
    }

    @Override public void mkdirs(String path) throws AdapterException {
        try {
            Files.createDirectories(resolve(path));
        } catch (IOException e) {
            throw new AdapterException("创建目录失败: " + path, e);
        }
    }

    @Override public InputStream read(String path) throws AdapterException {
        Path p = resolve(path);
        if (!Files.isRegularFile(p)) throw new AdapterException("对象不存在: " + path);
        try {
            return Files.newInputStream(p);
        } catch (IOException e) {
            throw new AdapterException("打开失败: " + path, e);
        }
    }

    @Override public void write(String path, InputStream data, long length) throws AdapterException {
        Path target = resolve(path);
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            // 先写临时文件再改名：本地文件系统支持原子替换，这样即使中途失败也不会留下半个对象
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                data.transferTo(out);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new AdapterException("写入失败: " + path, e);
        }
    }

    @Override public void delete(String path) throws AdapterException {
        Path p = resolve(path);
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        } catch (IOException e) {
            throw new AdapterException("删除失败: " + path, e);
        }
    }

    /** 解析相对路径，并阻断 {@code ..} 与绝对路径穿越。 */
    private Path resolve(String path) {
        if (path == null) throw new IllegalArgumentException("path 不能为空");
        String p = path.replace('\\', '/');
        // 明确拒绝绝对路径：API 约定所有路径都是相对根目录的，
        // 悄悄把 "/foo" 当成 "foo" 会掩盖调用方的错误。
        if (p.startsWith("/")) throw new IllegalArgumentException("非法路径（必须是相对路径）: " + path);
        Path out = root.resolve(p).normalize();
        if (!out.startsWith(root)) throw new IllegalArgumentException("非法路径（越出根目录）: " + path);
        return out;
    }

    public Path root() { return root; }
}
