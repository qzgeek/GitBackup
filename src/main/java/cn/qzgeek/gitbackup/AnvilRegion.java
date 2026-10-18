package cn.qzgeek.gitbackup;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anvil 区域文件读写。
 *
 * <p>实测格式（Lophine 26.1.2 测试服）：文件头 8192 字节，前 4096 为 1024 个位置表项
 * （4 字节：高 24 位为起始扇区、低 8 位为扇区数），后 4096 为时间戳表。记录格式为
 * [int 长度][byte 压缩方式][数据]，长度 = 数据字节数（不含自身 4 字节）。
 *
 * <p>注意：本服务器上部分 region 文件（例如插件生成的资源世界）末尾没有补齐到 4096 的整数倍，
 * 最后一条记录直接结束于文件末尾。因此"目录项声明的扇区数"可能超出实际文件长度，
 * 只有最后一条记录允许如此。读写均按该真实格式处理，不使用"文件长度必须是 4096 倍数"的假设。
 */
final class AnvilRegion {
    static final int SECTOR = 4096;
    static final int HEADER_SECTORS = 2;
    static final int SLOTS = 1024;
    static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;

    private AnvilRegion() {}

    /** 一个区块槽位。bytes 为磁盘原始记录（含 4 字节长度前缀）。 */
    record Slot(int index, int sector, int sectors, byte[] bytes) {
        int chunkXOffset() { return index & 31; }
        int chunkZOffset() { return index >>> 5; }
        int requiredSectors() { return (bytes.length + SECTOR - 1) / SECTOR; }
        boolean fitsInPlace() { return requiredSectors() <= sectors; }
    }

    static int slotIndex(int localX, int localZ) {
        if (localX < 0 || localX > 31 || localZ < 0 || localZ > 31)
            throw new IllegalArgumentException("区块偏移越界: " + localX + "," + localZ);
        return (localZ << 5) | localX;
    }

    static Map<Integer, Slot> readSlots(Path file) throws IOException {
        long size = Files.size(file);
        if (size < SECTOR * HEADER_SECTORS)
            throw new IOException("region 文件过小，不是有效 Anvil 文件: " + file);
        byte[] header = new byte[SECTOR * HEADER_SECTORS];
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.wrap(header);
            while (buf.hasRemaining() && ch.read(buf) > 0) { /* fill */ }
        }
        Map<Integer, Slot> slots = new LinkedHashMap<>();
        ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < SLOTS; i++) {
            int loc = hb.getInt(i * 4);
            int sector = (loc >>> 8) & 0xFFFFFF;
            int sectors = loc & 0xFF;
            if (sector == 0 && sectors == 0) continue;
            if (sector < HEADER_SECTORS || sectors < 1)
                throw new IOException("region 目录项损坏 slot=" + i + " file=" + file);
            long offset = (long) sector * SECTOR;
            if (offset + 4 > size)
                throw new IOException("region 目录项越界 slot=" + i + " file=" + file);
            byte[] prefix = new byte[4];
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
                readFully(ch, ByteBuffer.wrap(prefix), offset);
            }
            int length = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN).getInt();
            if (length < 1 || length > MAX_RECORD_BYTES)
                throw new IOException("region 记录长度非法 slot=" + i + " length=" + length + " file=" + file);
            long needEnd = offset + 4L + length;
            long declaredEnd = offset + (long) sectors * SECTOR;
            // 只有"文件最后一条记录"允许未补齐到扇区边界（真实存在的不补齐格式）。
            if (needEnd > size)
                throw new IOException("region 记录被截断 slot=" + i + " file=" + file);
            if (needEnd > declaredEnd)
                throw new IOException("region 记录超出目录声明扇区 slot=" + i + " file=" + file);
            byte[] bytes = new byte[4 + length];
            System.arraycopy(prefix, 0, bytes, 0, 4);
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
                readFully(ch, ByteBuffer.wrap(bytes, 4, length), offset + 4);
            }
            slots.put(i, new Slot(i, sector, sectors, bytes));
        }
        return slots;
    }

    /**
     * 就地替换记录：只改写记录数据本身，绝不改动目录表。
     *
     * <p>这是热恢复的关键安全前提：服务端把目录表缓存在内存里，只要目录表不变，
     * 服务端对其它区块的读取和后续写入都不会被破坏。新记录必须放得进原分配的扇区数，
     * 否则抛出 {@link InPlaceNotPossible}，由调用方退回"重启时重建"路径。
     */
    static void replaceInPlace(Path file, Map<Integer, byte[]> replacements) throws IOException {
        if (replacements.isEmpty()) return;
        Map<Integer, Slot> slots = readSlots(file);
        for (Map.Entry<Integer, byte[]> e : replacements.entrySet()) {
            Slot slot = slots.get(e.getKey());
            if (slot == null) throw new InPlaceNotPossible("slot " + e.getKey() + " 在该 region 中不存在");
            int need = (e.getValue().length + SECTOR - 1) / SECTOR;
            if (need > slot.sectors())
                throw new InPlaceNotPossible("slot " + e.getKey() + " 需要 " + need + " 个扇区，原分配 " + slot.sectors());
            if (e.getValue().length < 5) throw new IOException("拒绝写入空记录 slot=" + e.getKey());
            byte[] copy = e.getValue();
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.wrap(copy);
                long pos = (long) slot.sector() * SECTOR;
                while (buf.hasRemaining()) pos += ch.write(buf, pos);
            }
            // 校验落盘内容与期望完全一致
            byte[] actual = new byte[copy.length];
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
                readFully(ch, ByteBuffer.wrap(actual), (long) slot.sector() * SECTOR);
            }
            if (!java.util.Arrays.equals(actual, copy))
                throw new IOException("就地写入校验失败 slot=" + e.getKey() + " file=" + file);
        }
    }

    /** 冷态重写：重新分配全部扇区，可处理新增、替换与删除。只能在停服或世界加载前调用。 */
    static void rewrite(Path file, Map<Integer, byte[]> replacements, java.util.Set<Integer> removals) throws IOException {
        Map<Integer, Slot> slots = Files.exists(file) ? readSlots(file) : new LinkedHashMap<>();
        List<byte[]> ordered = new ArrayList<>();
        Map<Integer, byte[]> merged = new LinkedHashMap<>();
        for (Map.Entry<Integer, Slot> e : slots.entrySet()) {
            if (removals.contains(e.getKey())) continue;
            merged.put(e.getKey(), replacements.getOrDefault(e.getKey(), e.getValue().bytes()));
        }
        for (Map.Entry<Integer, byte[]> e : replacements.entrySet())
            if (!removals.contains(e.getKey())) merged.putIfAbsent(e.getKey(), e.getValue());

        int maxSlot = merged.isEmpty() ? -1 : merged.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        int totalSectors = HEADER_SECTORS;
        for (byte[] bytes : merged.values()) totalSectors += (bytes.length + SECTOR - 1) / SECTOR;
        if (maxSlot < 0) {
            Files.deleteIfExists(file);
            return;
        }
        byte[] out = new byte[totalSectors * SECTOR];
        ByteBuffer hb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        int next = HEADER_SECTORS;
        for (Map.Entry<Integer, byte[]> e : merged.entrySet()) {
            byte[] bytes = e.getValue();
            int sectors = (bytes.length + SECTOR - 1) / SECTOR;
            hb.putInt(e.getKey() * 4, (next << 8) | sectors);
            System.arraycopy(bytes, 0, out, next * SECTOR, bytes.length);
            next += sectors;
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".gitbackup.tmp");
        Files.createDirectories(file.getParent());
        Files.write(tmp, out);
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        ordered.clear();
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos);
            if (n < 0) throw new IOException("读取 region 文件时意外到达文件末尾");
            pos += n;
        }
    }

    /** 就地替换不可行（需要更多扇区或槽位不存在），必须改走冷态重建。 */
    static final class InPlaceNotPossible extends IOException {
        InPlaceNotPossible(String message) { super(message); }
    }
}
