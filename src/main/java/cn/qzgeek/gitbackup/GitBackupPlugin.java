package cn.qzgeek.gitbackup;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GitBackupPlugin extends JavaPlugin {
    private static final String DEFAULT_CONFIG = """
            repository:
              local-path: backup-repository
              remote-url: ""
              branch: main
              protocol: https
              https-username: ""
              https-token: ""
              ssh-key-file: ssh/id_ed25519
            worlds:
              auto-discover: true
            performance:
              worker-threads: 2
            schedules:
              hourly:
                cron: "0 0 * * * ?"
              daily:
                cron: "0 0 4 * * ?"
            restore:
              create-pre-restore-snapshot: true
              teleport-players: true
            """;

    private ExecutorService executor;
    private GitBackupConfig config;
    private GitRepository repository;
    private SnapshotEngine snapshots;

    @Override public void onEnable() {
        try {
            writeDefaultConfig();
            saveConfig();
            config = GitBackupConfig.load(getConfig(), getDataFolder().toPath());
            executor = Executors.newFixedThreadPool(config.workerThreads, r -> {
                Thread t = new Thread(r, "GitBackup-Worker"); t.setDaemon(true); return t;
            });
            repository = GitRepository.open(this, config);
            snapshots = new SnapshotEngine(this, repository);
            if (getCommand("gitbackup") != null) getCommand("gitbackup").setExecutor(new GitBackupCommand(this));
            getLogger().info("GitBackup 已启用，自动发现世界数量: " + config.discoverWorlds().size());
        } catch (Exception e) {
            getLogger().severe("GitBackup 启动失败: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void writeDefaultConfig() throws IOException {
        Path file = getDataFolder().toPath().resolve("config.yml");
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) Files.writeString(file, DEFAULT_CONFIG);
        Path readme = getDataFolder().toPath().resolve("ssh/README.txt");
        Files.createDirectories(readme.getParent());
        if (!Files.exists(readme)) Files.writeString(readme, "将 SSH 私钥放入此目录，并在 config.yml 设置 ssh-key-file。\n");
    }

    void status(CommandSender sender) {
        sender.sendMessage("§aGitBackup 已启用");
        sender.sendMessage("§7仓库: " + repository.root());
        CompletableFuture.supplyAsync(() -> { try { return repository.head(); } catch (Exception e) { return e.getMessage(); } }, executor)
                .thenAccept(head -> Bukkit.getGlobalRegionScheduler().execute(this, () -> sender.sendMessage("§7HEAD: " + head)));
        sender.sendMessage("§7世界: " + config.discoverWorlds().stream().map(GitBackupConfig.WorldTarget::name).toList());
    }

    void backup(CommandSender sender, String name) {
        sender.sendMessage("§eGitBackup 正在异步创建存档快照...");
        CompletableFuture.supplyAsync(() -> {
            try {
                SnapshotEngine.SnapshotResult result = snapshots.backup(config.discoverWorlds());
                String commit = repository.commitAndPush("Backup " + name + " at " + Instant.now());
                return "§a备份完成：对象 " + result.objects() + "，提交 " + commit;
            } catch (Exception e) { throw new RuntimeException(e); }
        }, executor).whenComplete((message, error) -> Bukkit.getGlobalRegionScheduler().execute(this, () -> sender.sendMessage(error == null ? message : "§c备份失败：" + root(error))));
    }

    void list(CommandSender sender, String worldName, String chunk) {
        CompletableFuture.supplyAsync(() -> { try { return repository.head(); } catch (Exception e) { return e.getMessage(); } }, executor)
                .thenAccept(head -> Bukkit.getGlobalRegionScheduler().execute(this, () -> sender.sendMessage("§e当前仓库 HEAD: " + head)));
        sender.sendMessage("§7可用时间点由 Git 提交提供；请将提交短哈希作为 restore 命令最后一个参数。");
        sender.sendMessage("§7目标世界: " + worldName + (chunk == null ? "" : "，区块: " + chunk));
    }

    void restore(CommandSender sender, String worldName, Set<SnapshotEngine.ChunkKey> chunks, String revision) {
        GitBackupConfig.WorldTarget target = config.discoverWorlds().stream().filter(w -> w.name().equals(worldName)).findFirst().orElse(null);
        if (target == null) { sender.sendMessage("§c找不到已加载世界: " + worldName); return; }
        String rev = revision == null || revision.isBlank() ? "HEAD" : revision;
        sender.sendMessage("§e恢复任务已提交，目标区块数: " + chunks.size() + "，版本: " + rev);
        CompletableFuture.runAsync(() -> {
            try {
                snapshots.backup(List.of(target));
                repository.commitAndPush("Pre-restore snapshot " + Instant.now());
                snapshots.restoreChunks(rev, target, "region", chunks);
                snapshots.restoreChunks(rev, target, "entities", chunks);
                snapshots.restoreChunks(rev, target, "poi", chunks);
            } catch (Exception e) { throw new RuntimeException(e); }
        }, executor).whenComplete((v, error) -> Bukkit.getGlobalRegionScheduler().execute(this, () -> sender.sendMessage(error == null ? "§a恢复完成。请确认目标区块已重新加载。" : "§c恢复失败：" + root(error))));
    }

    void rollback(CommandSender sender, String id) {
        CompletableFuture.supplyAsync(() -> { try { return repository.head(); } catch (Exception e) { return e.getMessage(); } }, executor)
                .thenAccept(head -> Bukkit.getGlobalRegionScheduler().execute(this, () -> sender.sendMessage("§e回滚记录查询尚未接入 TUI；恢复前快照已经以 Git 提交保存。最近 HEAD: " + head)));
    }
    private static String root(Throwable t) { while (t.getCause() != null) t = t.getCause(); return t.getMessage(); }

    @Override public void onDisable() { if (executor != null) executor.shutdownNow(); if (repository != null) repository.close(); }
}
