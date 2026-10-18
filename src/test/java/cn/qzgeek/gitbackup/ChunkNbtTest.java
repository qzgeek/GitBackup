package cn.qzgeek.gitbackup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkNbtTest {
    @Test void parsesCoordinatesFromZlibRecord() throws Exception {
        byte[] record = TestFixtures.record(TestFixtures.chunkNbt(-11, 21, new byte[]{1, 2, 3}));
        ChunkNbt.Header header = ChunkNbt.parse(record);
        assertEquals(-11, header.x());
        assertEquals(21, header.z());
        assertEquals("full", header.status());
        assertEquals(2, header.compression());
    }

    @Test void rejectsTornRecord() throws Exception {
        byte[] record = TestFixtures.record(TestFixtures.chunkNbt(1, 1, new byte[500]));
        byte[] torn = java.util.Arrays.copyOf(record, record.length - 40);
        assertThrows(java.io.IOException.class, () -> ChunkNbt.parse(torn));
    }

    @Test void rejectsCorruptedCompressedPayload() throws Exception {
        byte[] record = TestFixtures.record(TestFixtures.chunkNbt(1, 1, new byte[500]));
        record[record.length / 2] ^= 0x7F;
        assertThrows(java.io.IOException.class, () -> ChunkNbt.parse(record));
    }

    @Test void reportsUnsupportedCompressionInsteadOfFailing() throws Exception {
        byte[] record = TestFixtures.record(TestFixtures.chunkNbt(1, 1, new byte[10]));
        record[4] = (byte) 127; // 服务端自定义压缩
        assertThrows(ChunkNbt.Unsupported.class, () -> ChunkNbt.parse(record));
    }

    @Test void cronValidation() {
        assertNull(ScheduleRunner.validate("0 0 * * * ?"));
        assertNull(ScheduleRunner.validate("*/10 * * * * ?"));
        assertNull(ScheduleRunner.validate("0 30 2 ? * MON"));
        assertNotNull(ScheduleRunner.validate("not a cron"));
        assertNotNull(ScheduleRunner.validate("0 0 * *"));
    }
}
