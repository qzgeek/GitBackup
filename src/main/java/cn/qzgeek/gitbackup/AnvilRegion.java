package cn.qzgeek.gitbackup;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class AnvilRegion {
    static final int SECTOR = 4096;
    private AnvilRegion() {}

    static List<Record> read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < SECTOR * 2) return List.of();
        List<Record> records = new ArrayList<>();
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        for (int slot = 0; slot < 1024; slot++) {
            int location = header.getInt(slot * 4);
            int sector = (location >>> 8) & 0xFFFFFF;
            int count = location & 0xFF;
            if (sector < 2 || count < 1 || (long) sector * SECTOR + 5 > bytes.length) continue;
            int offset = sector * SECTOR;
            int length = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            if (length < 2 || length > count * SECTOR - 4 || offset + 4L + length > bytes.length) continue;
            byte[] record = java.util.Arrays.copyOfRange(bytes, offset, offset + 4 + length);
            int localX = slot & 31;
            int localZ = slot >>> 5;
            records.add(new Record(localX, localZ, record));
        }
        return records;
    }

    static void writeRegion(Path file, List<Record> records) throws IOException {
        Files.createDirectories(file.getParent());
        byte[] out = new byte[SECTOR * 2];
        int nextSector = 2;
        ByteBuffer header = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        for (Record record : records) {
            int sectors = (record.bytes.length + 4 + SECTOR - 1) / SECTOR;
            int offset = nextSector * SECTOR;
            if (out.length < (long) (nextSector + sectors) * SECTOR) {
                out = java.util.Arrays.copyOf(out, (nextSector + sectors) * SECTOR);
                header = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
            }
            System.arraycopy(record.bytes, 0, out, offset, record.bytes.length);
            header.putInt((record.localZ * 32 + record.localX) * 4, (nextSector << 8) | sectors);
            nextSector += sectors;
        }
        Files.write(file, out);
    }

    record Record(int localX, int localZ, byte[] bytes) {}
}
