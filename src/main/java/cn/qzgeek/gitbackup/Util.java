package cn.qzgeek.gitbackup;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 与聊天组件无关的通用工具，离线 CLI 也能用（不依赖 Adventure）。 */
final class Util {
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private Util() {}

    static String ago(Instant time) {
        if (time == null) return "未知";
        Duration d = Duration.between(time, Instant.now());
        long minutes = Math.max(0, d.toMinutes());
        if (minutes < 60) return minutes + " 分钟前";
        long hours = minutes / 60;
        if (hours < 48) return hours + " 小时前";
        return (hours / 24) + " 天前";
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MiB", bytes / 1048576.0);
        return String.format("%.2f GiB", bytes / 1073741824.0);
    }
}
