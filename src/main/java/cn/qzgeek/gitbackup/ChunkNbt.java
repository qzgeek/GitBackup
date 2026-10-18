package cn.qzgeek.gitbackup;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * 区块记录校验用最小 NBT 解析器。
 *
 * <p>目的不是完整读取区块，而是在备份时确认"这条记录是完整、可解压、坐标自洽的"：
 * 解压后校验 xPos/zPos 是否与所在 region 槽位一致。这样即使备份瞬间恰好撞上服务端写盘，
 * 也不会把撕裂的字节写进历史版本。
 */
final class ChunkNbt {
    private ChunkNbt() {}

    /**
     * 解析结果。
     *
     * <p>坐标可能为空：
     * <ul>
     *   <li>region 区块：有 xPos/zPos</li>
     *   <li>entities 区块：只有 Position（int[2] 的区块坐标）</li>
     *   <li>poi 区块：没有任何坐标信息，只能靠可解压性判断完整性</li>
     * </ul>
     * 因此坐标为空时表示"该类型不携带坐标"，不是校验失败。
     */
    record Header(Integer x, Integer z, String status, int compression) {}

    static Header parse(byte[] record) throws IOException, Unsupported {
        if (record.length < 6) throw new IOException("记录过短");
        int length = ((record[0] & 0xFF) << 24) | ((record[1] & 0xFF) << 16)
                | ((record[2] & 0xFF) << 8) | (record[3] & 0xFF);
        if (length != record.length - 4) throw new IOException("长度前缀与记录长度不一致");
        int compression = record[4] & 0xFF;
        byte[] raw = decompress(record, 5, length, compression);
        Integer[] xz = new Integer[2];
        String[] status = new String[1];
        parseRoot(raw, xz, status);
        return new Header(xz[0], xz[1], status[0], compression);
    }

    static byte[] decompress(byte[] record, int offset, int length, int compression) throws IOException, Unsupported {
        byte[] payload = new byte[length - 1];
        System.arraycopy(record, offset, payload, 0, payload.length);
        switch (compression) {
            case 1 -> { // gzip：自带 CRC32，解压即校验
                try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                    return in.readAllBytes();
                }
            }
            case 2 -> { // zlib：自带 Adler-32
                Inflater inflater = new Inflater();
                try {
                    inflater.setInput(payload);
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(256, length * 4));
                    byte[] buf = new byte[8192];
                    while (!inflater.finished()) {
                        int n;
                        try {
                            n = inflater.inflate(buf);
                        } catch (java.util.zip.DataFormatException e) {
                            throw new IOException("zlib 数据校验失败（记录可能被截断）: " + e.getMessage());
                        }
                        if (n == 0) {
                            if (inflater.needsInput() || inflater.needsDictionary()) throw new IOException("zlib 数据不完整");
                            else throw new IOException("zlib 解压停滞");
                        }
                        out.write(buf, 0, n);
                    }
                    return out.toByteArray();
                } finally {
                    inflater.end();
                }
            }
            case 3 -> { // 未压缩
                return payload;
            }
            default -> throw new Unsupported(compression);
        }
    }

    private static void parseRoot(byte[] raw, Integer[] xz, String[] status) throws IOException {
        int p = 0;
        byte type = readByte(raw, p++);
        if (type != 10) throw new IOException("根标签不是 compound");
        int nameLen = readShort(raw, p); p += 2;
        if (nameLen < 0 || p + nameLen > raw.length) throw new IOException("根标签名越界");
        p += nameLen;
        while (true) {
            if (p >= raw.length) break;
            byte t = readByte(raw, p++);
            if (t == 0) break;
            int nlen = readShort(raw, p); p += 2;
            if (nlen < 0 || p + nlen > raw.length) throw new IOException("标签名越界");
            String name = new String(raw, p, nlen, java.nio.charset.StandardCharsets.UTF_8);
            p += nlen;
            switch (t) {
                case 1 -> p += 1;
                case 2 -> p += 2;
                case 3 -> {
                    int v = readInt(raw, p); p += 4;
                    if (name.equals("xPos")) xz[0] = v;
                    else if (name.equals("zPos")) xz[1] = v;
                }
                case 4 -> p += 8;
                case 5 -> p += 4;
                case 6 -> p += 8;
                case 7 -> { int n = readInt(raw, p); p += 4; if (n < 0 || p + n > raw.length) throw new IOException("byte 数组越界"); p += n; }
                case 8 -> {
                    int sl = readShort(raw, p); p += 2;
                    if (sl < 0 || p + sl > raw.length) throw new IOException("字符串越界");
                    if (name.equals("Status")) status[0] = new String(raw, p, sl, java.nio.charset.StandardCharsets.UTF_8);
                    p += sl;
                }
                case 9 -> {
                    byte et = readByte(raw, p++);
                    int n = readInt(raw, p); p += 4;
                    if (n < 0 || n > raw.length) throw new IOException("列表长度非法");
                    for (int i = 0; i < n; i++) p = skipPayload(raw, p, et);
                }
                case 10 -> p = skipCompound(raw, p);
                case 11 -> {
                    int n = readInt(raw, p); p += 4;
                    if (n < 0 || p + (long) n * 4 > raw.length) throw new IOException("int 数组越界");
                    // 实体区块用 Position=[chunkX, chunkZ] 标识自己
                    if (name.equals("Position") && n == 2 && xz[0] == null) {
                        xz[0] = readInt(raw, p);
                        xz[1] = readInt(raw, p + 4);
                    }
                    p += n * 4;
                }
                case 12 -> { int n = readInt(raw, p); p += 4; if (n < 0 || p + (long) n * 8 > raw.length) throw new IOException("long 数组越界"); p += n * 8; }
                default -> throw new IOException("未知 NBT 类型 " + t);
            }
            if (p > raw.length) throw new IOException("NBT 越界（记录可能被截断）");
        }
        if ((xz[0] == null) != (xz[1] == null)) throw new IOException("区块坐标字段不完整");
    }

    private static int skipCompound(byte[] raw, int p) throws IOException {
        while (true) {
            if (p >= raw.length) throw new IOException("compound 未闭合");
            byte t = readByte(raw, p++);
            if (t == 0) return p;
            int nlen = readShort(raw, p); p += 2;
            if (nlen < 0 || p + nlen > raw.length) throw new IOException("标签名越界");
            p += nlen;
            p = skipPayload(raw, p, t);
        }
    }

    private static int skipPayload(byte[] raw, int p, byte t) throws IOException {
        switch (t) {
            case 1 -> { return p + 1; }
            case 2 -> { return p + 2; }
            case 3 -> { return p + 4; }
            case 4 -> { return p + 8; }
            case 5 -> { return p + 4; }
            case 6 -> { return p + 8; }
            case 7 -> {
                int n = readInt(raw, p); p += 4;
                if (n < 0 || p + (long) n > raw.length) throw new IOException("byte 数组越界");
                return p + n;
            }
            case 8 -> {
                int n = readShort(raw, p); p += 2;
                if (n < 0 || p + n > raw.length) throw new IOException("字符串越界");
                return p + n;
            }
            case 9 -> {
                byte et = readByte(raw, p++);
                int n = readInt(raw, p); p += 4;
                if (n < 0 || n > raw.length) throw new IOException("列表长度非法");
                for (int i = 0; i < n; i++) p = skipPayload(raw, p, et);
                return p;
            }
            case 10 -> { return skipCompound(raw, p); }
            case 11 -> {
                int n = readInt(raw, p); p += 4;
                if (n < 0 || p + (long) n * 4 > raw.length) throw new IOException("int 数组越界");
                return p + n * 4;
            }
            case 12 -> {
                int n = readInt(raw, p); p += 4;
                if (n < 0 || p + (long) n * 8 > raw.length) throw new IOException("long 数组越界");
                return p + n * 8;
            }
            default -> throw new IOException("未知 NBT 类型 " + t);
        }
    }

    private static byte readByte(byte[] b, int p) throws IOException {
        if (p < 0 || p >= b.length) throw new IOException("越界");
        return b[p];
    }
    private static int readShort(byte[] b, int p) throws IOException {
        if (p < 0 || p + 2 > b.length) throw new IOException("越界");
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }
    private static int readInt(byte[] b, int p) throws IOException {
        if (p < 0 || p + 4 > b.length) throw new IOException("越界");
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    /** 压缩方式不受支持（例如服务端自定义压缩），调用方应跳过内容校验而不是拒绝该区块。 */
    static final class Unsupported extends Exception {
        final int compression;
        Unsupported(int compression) { super("不支持的压缩方式 " + compression); this.compression = compression; }
    }
}
