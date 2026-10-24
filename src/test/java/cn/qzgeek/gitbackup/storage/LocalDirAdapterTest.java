package cn.qzgeek.gitbackup.storage;

import cn.qzgeek.gitbackup.storage.local.LocalDirAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 统一存储 API 的参照实现测试：这层必须对所有适配器成立。 */
class LocalDirAdapterTest {
    @TempDir Path tmp;

    private LocalDirAdapter adapter() throws Exception { return new LocalDirAdapter(tmp.resolve("root")); }

    @Test void writeReadSizeExists() throws Exception {
        StorageAdapter a = adapter();
        byte[] data = "hello-gitbackup".getBytes(StandardCharsets.UTF_8);
        a.write("minecraft/overworld/test.txt", new ByteArrayInputStream(data), data.length);

        assertTrue(a.exists("minecraft/overworld/test.txt"));
        assertEquals(data.length, a.size("minecraft/overworld/test.txt"));
        try (InputStream in = a.read("minecraft/overworld/test.txt")) {
            assertArrayEquals(data, in.readAllBytes());
        }
        assertFalse(a.exists("minecraft/overworld/nope.txt"));
        assertEquals(-1, a.size("minecraft/overworld/nope.txt"));
    }

    @Test void listReturnsDirectChildrenSortedAndSkipsMissingDir() throws Exception {
        StorageAdapter a = adapter();
        a.write("w/b.bin", new ByteArrayInputStream(new byte[]{1}), 1);
        a.write("w/a.bin", new ByteArrayInputStream(new byte[]{1, 2}), 2);
        a.mkdirs("w/sub");

        List<StorageEntry> entries = a.list("w");
        assertEquals(3, entries.size());
        assertEquals("a.bin", entries.get(0).name());
        assertEquals(2, entries.get(0).size());
        assertEquals("b.bin", entries.get(1).name());
        assertEquals("sub", entries.get(2).name());
        assertTrue(entries.get(2).directory());

        assertTrue(a.list("no/such/dir").isEmpty(), "不存在的目录应返回空列表而不是抛错");
    }

    @Test void deleteRemovesFileAndTree() throws Exception {
        StorageAdapter a = adapter();
        a.write("w/x/1.bin", new ByteArrayInputStream(new byte[]{1}), 1);
        a.write("w/x/2.bin", new ByteArrayInputStream(new byte[]{2}), 1);
        a.delete("w/x");
        assertFalse(a.exists("w/x/1.bin"));
        a.delete("w/x"); // 幂等
    }

    @Test void rejectsPathTraversal() throws Exception {
        StorageAdapter a = adapter();
        assertThrows(IllegalArgumentException.class, () -> a.exists("../escape"));
        assertThrows(IllegalArgumentException.class, () -> a.exists("/etc/passwd"));
    }

    /** 64 MiB 流式读写 + 摘要校验，验证大对象不会一次性读进内存。 */
    @Test void streamsLargeObject() throws Exception {
        StorageAdapter a = adapter();
        int size = 64 * 1024 * 1024;
        byte[] block = new byte[1 << 20];
        new java.util.Random(42).nextBytes(block);

        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < size / block.length; i++) expected.update(block);

        a.write("big/pack.pk", new RepeatingStream(block, size), size);

        assertEquals(size, a.size("big/pack.pk"));
        MessageDigest actual = MessageDigest.getInstance("SHA-256");
        try (InputStream in = a.read("big/pack.pk")) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) actual.update(buf, 0, n);
        }
        assertArrayEquals(expected.digest(), actual.digest());
    }

    @Test void concurrentWritesToDifferentPaths() throws Exception {
        StorageAdapter a = adapter();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int id = i;
            pool.execute(() -> {
                try {
                    byte[] data = ("payload-" + id).getBytes(StandardCharsets.UTF_8);
                    for (int k = 0; k < 20; k++)
                        a.write("c/" + id + "/" + k + ".bin", new ByteArrayInputStream(data), data.length);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();
        for (int i = 0; i < threads; i++) assertEquals(20, a.list("c/" + i).size());
        assertTrue(Files.exists(tmp.resolve("root/c/0/0.bin")));
    }

    @Test void registryExposesBuiltinAndRejectsUnknown() throws Exception {
        AdapterRegistry registry = new AdapterRegistry(Logger.getLogger("test"));
        assertTrue(registry.has("local"), "应内置 local 适配器");
        StorageAdapter a = registry.create("local", Map.of("root", tmp.resolve("reg").toString()));
        assertEquals("local", a.type());
        assertThrows(IllegalArgumentException.class, () -> registry.create("nope", Map.of()));
    }

    /** 重复同一段数据的流，用来模拟大对象写入而不必真的分配 64MiB 数组。 */
    private static final class RepeatingStream extends InputStream {
        private final byte[] block;
        private final long total;
        private int offset;
        private int blockPos;

        RepeatingStream(byte[] block, long total) {
            this.block = block;
            this.total = total;
        }

        @Override public int read() {
            if (offset >= total) return -1;
            int b = block[blockPos] & 0xFF;
            blockPos++;
            if (blockPos >= block.length) blockPos = 0;
            offset++;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) {
            if (offset >= total) return -1;
            int n = (int) Math.min(len, total - offset);
            int written = 0;
            while (written < n) {
                int chunk = Math.min(n - written, block.length - blockPos);
                System.arraycopy(block, blockPos, b, off + written, chunk);
                blockPos += chunk;
                if (blockPos >= block.length) blockPos = 0;
                written += chunk;
                offset += chunk;
            }
            return n;
        }
    }
}
