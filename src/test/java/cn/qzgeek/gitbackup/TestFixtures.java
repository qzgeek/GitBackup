package cn.qzgeek.gitbackup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Deflater;

/** 测试用：构造真实的 Anvil 文件与区块 NBT，避免依赖服务端。 */
final class TestFixtures {
    private TestFixtures() {}

    /** 构造一个最小但结构真实的区块 NBT（compound{xPos,zPos,Status,Sections(空列表)}）。 */
    static byte[] chunkNbt(int x, int z, byte[] filler) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(10); // TAG_Compound
        writeShort(out, 0); // 根名称长度 0
        writeTagInt(out, "xPos", x);
        writeTagInt(out, "zPos", z);
        writeTagString(out, "Status", "full");
        writeTagByteArray(out, "Filler", filler);
        // 空列表示例，确保解析器能正确跳过复杂标签
        out.write(9);
        writeShort(out, "sections".length());
        out.writeBytes("sections".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.write(10); // 元素类型 compound
        writeInt(out, 0);
        out.write(0); // 结束根 compound
        return out.toByteArray();
    }

    /** 把原始 NBT 包成 region 记录（zlib 压缩），返回含 4 字节长度前缀的记录。 */
    static byte[] record(byte[] nbt) {
        byte[] compressed = deflate(nbt);
        ByteBuffer buf = ByteBuffer.allocate(4 + 1 + compressed.length).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(1 + compressed.length);
        buf.put((byte) 2);
        buf.put(compressed);
        return buf.array();
    }

    /** 不可压缩内容，避免 deflate 把"大记录"压成小记录导致测试失去意义。 */
    static byte[] incompressible(int size, long seed) {
        byte[] out = new byte[size];
        new java.util.Random(seed).nextBytes(out);
        return out;
    }

    static byte[] deflate(byte[] data) {
        Deflater d = new Deflater();
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return out.toByteArray();
    }

    /**
     * 写出一个 region 文件。
     *
     * @param padded true 时补齐到 4096 边界（vanilla 行为），false 时最后一条记录直接结束（本服务器真实行为）
     */
    static Path writeRegion(Path file, Map<Integer, byte[]> records, boolean padded) throws IOException {
        int total = AnvilRegion.HEADER_SECTORS;
        for (byte[] r : records.values()) total += (r.length + AnvilRegion.SECTOR - 1) / AnvilRegion.SECTOR;
        long size = padded ? (long) total * AnvilRegion.SECTOR : -1;
        if (!padded) {
            int maxEnd = AnvilRegion.HEADER_SECTORS * AnvilRegion.SECTOR;
            int next = AnvilRegion.HEADER_SECTORS;
            for (byte[] r : records.values()) {
                maxEnd = Math.max(maxEnd, next * AnvilRegion.SECTOR + r.length);
                next += (r.length + AnvilRegion.SECTOR - 1) / AnvilRegion.SECTOR;
            }
            size = maxEnd;
        }
        byte[] out = new byte[(int) size];
        ByteBuffer header = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        int next = AnvilRegion.HEADER_SECTORS;
        for (Map.Entry<Integer, byte[]> e : records.entrySet()) {
            int sectors = (e.getValue().length + AnvilRegion.SECTOR - 1) / AnvilRegion.SECTOR;
            header.putInt(e.getKey() * 4, (next << 8) | sectors);
            System.arraycopy(e.getValue(), 0, out, next * AnvilRegion.SECTOR, e.getValue().length);
            next += sectors;
        }
        Files.createDirectories(file.getParent());
        Files.write(file, out);
        return file;
    }

    /** 建立一个带若干区块的世界目录。 */
    static Path world(Path dir, String dimension, int[][] chunks) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("level.dat"), "level-data-" + dimension);
        Path regionDir = dir.resolve("region");
        Map<Integer, byte[]> records = new LinkedHashMap<>();
        for (int[] c : chunks) {
            int rx = Math.floorDiv(c[0], 32), rz = Math.floorDiv(c[1], 32);
            int slot = AnvilRegion.slotIndex(Math.floorMod(c[0], 32), Math.floorMod(c[1], 32));
            records.put(slot, record(chunkNbt(c[0], c[1], ("payload-" + dimension + "-" + c[0] + "-" + c[1]).getBytes())));
        }
        writeRegion(regionDir.resolve("r." + Math.floorDiv(chunks[0][0], 32) + "." + Math.floorDiv(chunks[0][1], 32) + ".mca"),
                records, false);
        return dir;
    }

    private static void writeTagInt(ByteArrayOutputStream out, String name, int value) throws IOException {
        out.write(3);
        writeShort(out, name.length());
        out.writeBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        writeInt(out, value);
    }

    private static void writeTagString(ByteArrayOutputStream out, String name, String value) throws IOException {
        out.write(8);
        writeShort(out, name.length());
        out.writeBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] raw = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeShort(out, raw.length);
        out.writeBytes(raw);
    }

    private static void writeTagByteArray(ByteArrayOutputStream out, String name, byte[] value) throws IOException {
        out.write(7);
        writeShort(out, name.length());
        out.writeBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        writeInt(out, value.length);
        out.writeBytes(value);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
