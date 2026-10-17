package cn.qzgeek.gitbackup;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

final class GitBackupCommand implements TabExecutor {
    private final GitBackupPlugin plugin;
    GitBackupCommand(GitBackupPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gitbackup.admin")) { sender.sendMessage("§c没有权限。"); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) { plugin.status(sender); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "backup" -> plugin.backup(sender, args.length > 1 ? args[1] : "manual");
            case "list" -> list(sender, args);
            case "restore" -> restore(sender, args);
            case "rollback" -> plugin.rollback(sender, args.length > 1 ? args[1] : null);
            default -> help(sender);
        }
        return true;
    }

    private void list(CommandSender sender, String[] args) {
        String world = args.length > 1 ? args[1] : sender instanceof Player p ? p.getWorld().getName() : "";
        String chunk = args.length > 2 ? args[2] : null;
        plugin.list(sender, world, chunk);
    }

    private void restore(CommandSender sender, String[] args) {
        if (args.length < 4) { help(sender); return; }
        String mode = args[1].toLowerCase(Locale.ROOT);
        String world = args[2];
        try {
            if (mode.equals("chunk")) {
                Set<SnapshotEngine.ChunkKey> chunks = parseChunkRectangle(args[3], args.length > 4 ? args[4] : args[3]);
                String revision = args.length > 5 ? args[5] : null;
                plugin.restore(sender, world, chunks, revision);
            } else if (mode.equals("coordinate")) {
                int[] a = parseCoordinate(args[3]); int[] b = parseCoordinate(args[4]);
                Set<SnapshotEngine.ChunkKey> chunks = new HashSet<>();
                for (int x = Math.min(a[0], b[0]) >> 4; x <= (Math.max(a[0], b[0]) >> 4); x++)
                    for (int z = Math.min(a[2], b[2]) >> 4; z <= (Math.max(a[2], b[2]) >> 4); z++) chunks.add(new SnapshotEngine.ChunkKey(x, z));
                String revision = args.length > 5 ? args[5] : null;
                plugin.restore(sender, world, chunks, revision);
            } else help(sender);
        } catch (IllegalArgumentException e) { sender.sendMessage("§c参数错误：" + e.getMessage()); }
    }

    private static Set<SnapshotEngine.ChunkKey> parseChunkRectangle(String first, String second) {
        int[] a = parsePair(first), b = parsePair(second); Set<SnapshotEngine.ChunkKey> result = new HashSet<>();
        for (int x = Math.min(a[0], b[0]); x <= Math.max(a[0], b[0]); x++) for (int z = Math.min(a[1], b[1]); z <= Math.max(a[1], b[1]); z++) result.add(new SnapshotEngine.ChunkKey(x, z));
        return result;
    }
    private static int[] parsePair(String value) { String[] s = value.split(","); if (s.length != 2) throw new IllegalArgumentException("区块坐标应为 x,z"); return new int[]{Integer.parseInt(s[0]), Integer.parseInt(s[1])}; }
    private static int[] parseCoordinate(String value) { String[] s = value.split(","); if (s.length != 3) throw new IllegalArgumentException("坐标应为 x,y,z"); return new int[]{Integer.parseInt(s[0]), Integer.parseInt(s[1]), Integer.parseInt(s[2])}; }
    private static void help(CommandSender s) { s.sendMessage("§e/gitbackup status | backup [配置名] | list [世界 区块] | restore chunk <世界> <x,z> <x,z> [提交] | restore coordinate <世界> <x,y,z> <x,y,z> [提交] | rollback [记录ID]"); }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return partial(args[0], List.of("status", "backup", "list", "restore", "rollback"));
        if (args.length == 2 && args[0].equalsIgnoreCase("restore")) return partial(args[1], List.of("chunk", "coordinate"));
        return List.of();
    }
    private static List<String> partial(String p, List<String> values) { return values.stream().filter(v -> v.startsWith(p.toLowerCase(Locale.ROOT))).toList(); }
}
