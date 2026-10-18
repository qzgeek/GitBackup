package cn.qzgeek.gitbackup;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 命令入口。全部子命令只受 {@code gitbackup.admin} 单一权限控制。
 *
 * <p>所有耗时动作都交给插件内部异步线程；这里只做参数解析和聊天栏 TUI 渲染。
 */
final class GitBackupCommand implements TabExecutor {
    private static final String PERMISSION = "gitbackup.admin";
    private final GitBackupPlugin plugin;

    GitBackupCommand(GitBackupPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Text.error("你没有 gitbackup.admin 权限。"));
            return true;
        }
        try {
            if (args.length == 0) { help(sender); return true; }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "status" -> status(sender);
                case "backup" -> backup(sender, args.length > 1 ? args[1] : null);
                case "list" -> list(sender, args);
                case "restore" -> restore(sender, args);
                case "rollback" -> rollback(sender, args.length > 1 ? args[1] : null,
                        args.length > 2 ? args[2] : null);
                default -> help(sender);
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Text.error("参数错误: " + e.getMessage()));
        }
        return true;
    }

    // ------------------------------------------------------------------ status

    private void status(CommandSender sender) {
        GitBackupConfig cfg = plugin.settings();
        sender.sendMessage(Text.title("GitBackup v" + GitBackupPlugin.VERSION));
        sender.sendMessage(Text.row(Component.text("仓库").color(net.kyori.adventure.text.format.NamedTextColor.GRAY),
                Component.text(plugin.store().dir().toString()).color(net.kyori.adventure.text.format.NamedTextColor.WHITE)));
        sender.sendMessage(Text.row(Component.text("远端").color(net.kyori.adventure.text.format.NamedTextColor.GRAY),
                Component.text(plugin.store().hasRemote() ? plugin.store().remoteUrl() : "未配置（仅本地提交）")
                        .color(net.kyori.adventure.text.format.NamedTextColor.WHITE)));
        sender.sendMessage(Text.row(Component.text("最近备份").color(net.kyori.adventure.text.format.NamedTextColor.GRAY),
                Component.text(plugin.lastBackupSummary()).color(net.kyori.adventure.text.format.NamedTextColor.WHITE)));
        if (plugin.isBusy()) sender.sendMessage(Text.warn("当前有备份/恢复任务正在执行"));
        sender.sendMessage(Text.note("已启用世界:"));
        for (String key : cfg.enabledWorldKeys()) {
            Optional<WorldTarget> t = plugin.discoverTargets().stream().filter(w -> w.key().equals(key)).findFirst();
            sender.sendMessage(Text.row(Component.text(" • " + key).color(net.kyori.adventure.text.format.NamedTextColor.GREEN),
                    Component.text(t.map(w -> w.name() + " → " + w.root()).orElse("（当前未加载，仅配置存在）"))
                            .color(net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY)));
        }
        sender.sendMessage(Text.note("定时计划:"));
        List<String> describe = plugin.scheduler().describe();
        if (describe.isEmpty()) sender.sendMessage(Text.line("  （无）"));
        else describe.forEach(d -> sender.sendMessage(Text.line("  " + d)));
        sender.sendMessage(Text.row(
                Text.button("立即备份", "/gitbackup backup", "备份全部已启用世界"),
                Text.button("备份历史", "/gitbackup list", "查看提交历史"),
                Text.button("回滚记录", "/gitbackup rollback", "查看最近恢复记录")));
    }

    // ------------------------------------------------------------------ backup

    private void backup(CommandSender sender, String scheduleId) {
        Set<String> keys;
        if (scheduleId == null) {
            keys = plugin.settings().enabledWorldKeys();
        } else {
            var schedule = plugin.settings().schedules.stream()
                    .filter(s -> s.id().equalsIgnoreCase(scheduleId)).findFirst();
            if (schedule.isEmpty()) {
                sender.sendMessage(Text.error("没有名为 " + scheduleId + " 的定时计划。可用: "
                        + plugin.settings().schedules.stream().map(GitBackupConfig.Schedule::id).toList()));
                return;
            }
            keys = schedule.get().worlds().isEmpty()
                    ? plugin.settings().enabledWorldKeys() : new LinkedHashSet<>(schedule.get().worlds());
        }
        if (keys.isEmpty()) { sender.sendMessage(Text.error("没有已启用的世界可备份。")); return; }
        sender.sendMessage(Text.note("开始备份: " + keys));
        plugin.runBackup("手动:" + sender.getName(), keys, sender).whenComplete((summary, error) -> {
            if (error != null) plugin.reply(sender, Text.error("备份失败: " + GitBackupPlugin.rootMessage(error)));
            else plugin.reply(sender, Text.ok("备份完成: " + summary));
        });
    }

    // ------------------------------------------------------------------ list

    private void list(CommandSender sender, String[] args) {
        String worldArg = args.length > 1 ? args[1] : null;
        String chunkArg = args.length > 2 ? args[2] : null;
        if (worldArg == null) {
            if (sender instanceof Player p) {
                worldArg = p.getWorld().getName();
                chunkArg = (p.getLocation().getBlockX() >> 4) + "," + (p.getLocation().getBlockZ() >> 4);
            } else {
                sender.sendMessage(Text.warn("控制台需要指定世界: /gitbackup list <世界> [<区块x,z>]"));
                return;
            }
        }
        final String world = worldArg;
        final String chunk = chunkArg;
        plugin.replyNow(sender, Text.note("读取备份历史…"));
        plugin.runIo(() -> plugin.store().history(plugin.settings().historyLimit))
                .whenComplete((history, error) -> {
            if (error != null) {
                plugin.reply(sender, Text.error("读取历史失败: " + GitBackupPlugin.rootMessage(error)));
                return;
            }
            try {
                plugin.reply(sender, Text.title("备份历史" + (chunk == null ? "" : " · " + world + " @ " + chunk)));
                if (history.isEmpty()) { plugin.reply(sender, Text.line("（暂无提交）")); return; }
                int shown = 0;
                for (BackupStore.CommitInfo c : history) {
                    if (shown++ >= 15) { plugin.reply(sender, Text.line("… 共 " + history.size() + " 条")); break; }
                    String id = c.id().substring(0, 8);
                    Component row = Text.row(
                            Component.text(id).color(net.kyori.adventure.text.format.NamedTextColor.YELLOW),
                            Component.text(Util.TIME.format(c.time())).color(net.kyori.adventure.text.format.NamedTextColor.GRAY),
                            Component.text("(" + Util.ago(c.time()) + ")").color(net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY),
                            Component.text(c.message()).color(net.kyori.adventure.text.format.NamedTextColor.WHITE));
                    if (chunk != null) {
                        row = row.append(Text.button("恢复此区块", "/gitbackup restore chunk " + world + " " + chunk + " " + c.id(),
                                "把 " + world + " 的区块 " + chunk + " 恢复到 " + id));
                    }
                    plugin.reply(sender, row);
                }
                plugin.reply(sender, Text.note("提示: 恢复命令最后一项可填提交哈希（留空则显示本列表）。"));
            } catch (Exception e) {
                plugin.reply(sender, Text.error("读取历史失败: " + GitBackupPlugin.rootMessage(e)));
            }
        });
    }

    // ------------------------------------------------------------------ restore

    private void restore(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Text.error("用法: /gitbackup restore chunk <世界> <x,z> [<x,z>] [提交]"));
            sender.sendMessage(Text.error("       /gitbackup restore coordinate <世界> <x,y,z> <x,y,z> [提交]"));
            return;
        }
        String mode = args[1].toLowerCase(Locale.ROOT);
        String worldArg = args[2];
        Set<RestoreEngine.Chunk> chunks = new LinkedHashSet<>();
        String revision = null;
        if ("chunk".equals(mode)) {
            int i = 3;
            while (i < args.length && args[i].contains(",")) chunks.add(RestoreEngine.Chunk.parse(args[i++]));
            if (chunks.isEmpty()) throw new IllegalArgumentException("至少需要一个区块坐标 x,z");
            if (i < args.length) revision = args[i];
        } else if ("coordinate".equals(mode)) {
            if (args.length < 5) throw new IllegalArgumentException("需要两个坐标点");
            int[] a = parseCoordinate(args[3]);
            int[] b = parseCoordinate(args[4]);
            int minX = Math.min(a[0], b[0]) >> 4, maxX = Math.max(a[0], b[0]) >> 4;
            int minZ = Math.min(a[2], b[2]) >> 4, maxZ = Math.max(a[2], b[2]) >> 4;
            long count = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
            if (count > 4096) throw new IllegalArgumentException("范围过大（" + count + " 个区块），请分批恢复");
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) chunks.add(new RestoreEngine.Chunk(x, z));
            if (args.length > 5) revision = args[5];
        } else {
            throw new IllegalArgumentException("恢复模式只支持 chunk 或 coordinate");
        }

        if (revision == null || revision.isBlank()) {
            list(sender, new String[]{"list", worldArg, chunks.iterator().next().toString()});
            sender.sendMessage(Text.note("请在上表点击「恢复此区块」，或把提交哈希补在命令末尾。"));
            return;
        }
        plugin.restore(sender, worldArg, chunks, revision);
    }

    private static int[] parseCoordinate(String value) {
        String[] p = value.split(",");
        if (p.length != 3) throw new IllegalArgumentException("坐标格式应为 x,y,z: " + value);
        return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())};
    }

    // ------------------------------------------------------------------ rollback

    private void rollback(CommandSender sender, String id, String action) {
        if (id == null) {
            plugin.replyNow(sender, Text.note("读取回滚记录…"));
            plugin.runIo(plugin::readManifest).whenComplete((manifest, error) -> {
                if (error != null) {
                    plugin.reply(sender, Text.error("读取回滚记录失败: " + GitBackupPlugin.rootMessage(error)));
                    return;
                }
                try {
                    plugin.reply(sender, Text.title("最近恢复记录"));
                    if (manifest.rollbacks.isEmpty()) { plugin.reply(sender, Text.line("（暂无恢复记录）")); return; }
                    int shown = 0;
                    for (BackupManifest.RollbackEntry e : manifest.rollbacks) {
                        if (shown++ >= 10) break;
                        String state = e.rolledBack ? "已回滚" : (e.applied ? "已生效" : "待重启生效");
                        Component row = Text.row(
                                Component.text(e.id).color(net.kyori.adventure.text.format.NamedTextColor.YELLOW),
                                Component.text(e.world).color(net.kyori.adventure.text.format.NamedTextColor.GREEN),
                                Component.text(e.chunks + " 区块").color(net.kyori.adventure.text.format.NamedTextColor.GRAY),
                                Component.text(Util.TIME.format(java.time.Instant.parse(e.time)))
                                        .color(net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY),
                                Component.text(state).color(e.rolledBack
                                        ? net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY
                                        : net.kyori.adventure.text.format.NamedTextColor.AQUA));
                        if (!e.rolledBack) {
                            row = row.append(Text.button("回滚", "/gitbackup rollback " + e.id,
                                    "把 " + e.world + " 的这些区块恢复到 " + e.id + " 执行前的状态"));
                            row = row.append(Text.button("传送", "/gitbackup rollback " + e.id + " tp",
                                    "传送到这次恢复影响的区域中心"));
                        }
                        plugin.reply(sender, row);
                    }
                } catch (Exception e) {
                    plugin.reply(sender, Text.error("读取回滚记录失败: " + GitBackupPlugin.rootMessage(e)));
                }
            });
            return;
        }
        if ("tp".equalsIgnoreCase(action)) { teleportToRollback(sender, id); return; }
        plugin.rollback(sender, id);
    }

    /** 传送到某次恢复影响的区域中心，方便管理员现场查看结果。 */
    private void teleportToRollback(CommandSender sender, String id) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.error("只有玩家可以传送。"));
            return;
        }
        plugin.runIo(plugin::readManifest).whenComplete((manifest, error) -> {
            if (error != null) {
                plugin.reply(sender, Text.error("读取记录失败: " + GitBackupPlugin.rootMessage(error)));
                return;
            }
            try {
                BackupManifest.RollbackEntry entry = manifest.findRollback(id);
                if (entry == null) { plugin.reply(sender, Text.error("找不到记录: " + id)); return; }
                Set<RestoreEngine.Chunk> chunks = plugin.parseChunksPublic(entry.note);
                if (chunks.isEmpty()) { plugin.reply(sender, Text.error("该记录没有区块坐标")); return; }
                int cx = 0, cz = 0;
                for (var c : chunks) { cx += c.x(); cz += c.z(); }
                cx /= chunks.size(); cz /= chunks.size();
                String dim = entry.world.contains(":") ? entry.world.split(":")[1] : entry.world;
                var world = Bukkit.getWorld(dim);
                if (world == null) { plugin.reply(sender, Text.error("世界未加载: " + entry.world)); return; }
                int bx = (cx << 4) + 8, bz = (cz << 4) + 8;
                var loc = new org.bukkit.Location(world, bx + 0.5, world.getHighestBlockYAt(bx, bz) + 1, bz + 0.5);
                player.teleportAsync(loc);
                plugin.reply(sender, Text.ok("已传送到 " + entry.world + " 区块 " + cx + "," + cz));
            } catch (Exception e) {
                plugin.reply(sender, Text.error("传送失败: " + GitBackupPlugin.rootMessage(e)));
            }
        });
    }

    // ------------------------------------------------------------------ 帮助与补全

    private void help(CommandSender sender) {
        sender.sendMessage(Text.title("GitBackup 命令"));
        sender.sendMessage(Text.line("/gitbackup status"));
        sender.sendMessage(Text.line("/gitbackup backup [定时计划名]"));
        sender.sendMessage(Text.line("/gitbackup list [<世界> [<区块x,z>]]"));
        sender.sendMessage(Text.line("/gitbackup restore chunk <世界> <x,z> [<x,z>…] [提交]"));
        sender.sendMessage(Text.line("/gitbackup restore coordinate <世界> <x,y,z> <x,y,z> [提交]"));
        sender.sendMessage(Text.line("/gitbackup rollback [记录ID]"));
        sender.sendMessage(Text.note("提交省略时显示备份列表；y 坐标只用于换算区块，本身被忽略。"));
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args.length == 1)
            return filter(args[0], List.of("status", "backup", "list", "restore", "rollback"));
        if (args.length == 2 && args[0].equalsIgnoreCase("restore"))
            return filter(args[1], List.of("chunk", "coordinate"));
        if (args.length == 2 && (args[0].equalsIgnoreCase("list") || args[0].equalsIgnoreCase("restore")))
            return filter(args[1], plugin.discoverTargets().stream().map(WorldTarget::name).toList());
        if (args.length == 3 && args[0].equalsIgnoreCase("list"))
            return filter(args[2], List.of());
        if (args.length == 2 && args[0].equalsIgnoreCase("backup"))
            return filter(args[1], plugin.settings().schedules.stream().map(GitBackupConfig.Schedule::id).toList());
        return List.of();
    }

    private static List<String> filter(String prefix, List<String> values) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String v : values) if (v.toLowerCase(Locale.ROOT).startsWith(p)) out.add(v);
        return out;
    }
}
