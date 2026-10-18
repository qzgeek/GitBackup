package cn.qzgeek.gitbackup;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * GitBackup 主类。
 *
 * <p>线程模型（Folia）：
 * <ul>
 *   <li>所有 Git 与文件 IO 都在独立的异步单线程 executor 中执行，绝不占用区域线程；</li>
 *   <li>只有"卸载/重载区块、传送玩家、发送消息"这些必须落在线程归属上的动作，
 *       才通过 RegionScheduler 派发到对应区块所属区域线程；</li>
 *   <li>恢复的磁盘写入发生在区块已卸载之后，因此不与他人并发写同一 region 文件。</li>
 * </ul>
 */
public final class GitBackupPlugin extends JavaPlugin {
    static final String VERSION = "0.2.0";

    private GitBackupConfig settings;
    private BackupStore store;
    private SnapshotIndex index;
    private SnapshotEngine snapshots;
    private RestoreEngine restoreEngine;
    private ScheduleRunner scheduler;
    private ExecutorService io;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile String lastBackupSummary = "尚未执行";

    // ------------------------------------------------------------------ 生命周期

    @Override public void onLoad() {
        // 世界尚未加载：这是唯一可以安全重建 region 文件的时机。
        try {
            applyPendingRestores();
        } catch (Throwable t) {
            getLogger().severe("应用待生效恢复失败（世界数据未被修改）: " + t);
        }
    }

    @Override public void onEnable() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            ensureDefaultFiles();
            reloadConfig();
            settings = GitBackupConfig.load(getConfig(), getDataFolder().toPath());

            List<WorldTarget> discovered = discoverTargets();
            if (settings.mergeDiscovered(discovered)) {
                getLogger().info("发现新的世界并写入配置（默认关闭）: "
                        + discovered.stream().map(WorldTarget::key).toList());
                persistDiscoveredWorlds(discovered);
                reloadConfig();
                settings = GitBackupConfig.load(getConfig(), getDataFolder().toPath());
            }

            store = new BackupStore(settings.repositoryPath, settings.branch, settings.remoteUrl,
                    getDataFolder().toPath().resolve("ssh"), settings.sshKey,
                    settings.httpsUser, settings.httpsToken, settings.proxy);
            String format = store.formatVersionFromTree(store.head());
            if (format != null && !BackupStore.FORMAT.trim().equals(format))
                getLogger().warning("备份仓库格式版本为 " + format + "，与本插件期望的 " + BackupStore.FORMAT.trim() + " 不一致");

            index = SnapshotIndex.load(getDataFolder().toPath().resolve("index.json"));
            snapshots = new SnapshotEngine(store, index, settings.validate, settings.excludes);
            restoreEngine = new RestoreEngine(store);

            io = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "GitBackup-IO");
                t.setDaemon(true);
                return t;
            });

            scheduler = new ScheduleRunner(getLogger());
            scheduler.load(settings.schedules);
            Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> {
                try {
                    scheduler.tick(schedule -> {
                        Set<String> keys = schedule.worlds().isEmpty()
                                ? settings.enabledWorldKeys() : new LinkedHashSet<>(schedule.worlds());
                        runBackup("定时:" + schedule.id(), keys, null);
                    });
                } catch (Throwable t) {
                    getLogger().severe("定时器推进异常: " + t);
                }
            }, 5, 5, TimeUnit.SECONDS);

            GitBackupCommand command = new GitBackupCommand(this);
            var cmd = getCommand("gitbackup");
            if (cmd != null) { cmd.setExecutor(command); cmd.setTabCompleter(command); }

            getLogger().info("GitBackup v" + VERSION + " 已启用；仓库 " + store.dir()
                    + "，启用世界 " + settings.enabledWorldKeys());
            if (!store.hasRemote()) getLogger().warning("未配置 repository.remote-url：只做本地提交，不会上云。");
            for (String line : scheduler.describe()) getLogger().info("定时计划 " + line);
        } catch (Throwable t) {
            getLogger().severe("GitBackup 启动失败: " + t);
            t.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        if (io != null) { io.shutdownNow(); }
        if (store != null) store.close();
    }

    /**
     * 把新发现的世界补进配置。
     *
     * <p>必须"只追加"，不能整体重写：早期版本在这里直接重新生成默认配置，把用户的远端地址、
     * 认证、定时计划、代理、排除项全部覆盖掉了（实测踩到）。这里只补缺失的键。
     */
    private void persistDiscoveredWorlds(List<WorldTarget> discovered) {
        var config = getConfig();
        for (WorldTarget world : discovered) {
            String path = "worlds.list." + world.key();
            if (config.isConfigurationSection(path)) continue;
            config.set(path + ".enabled", false);
        }
        saveConfig();
    }

    private void ensureDefaultFiles() throws IOException {
        Path config = getDataFolder().toPath().resolve("config.yml");
        if (!Files.exists(config)) writeConfig(discoverTargets());
        Path sshDir = getDataFolder().toPath().resolve("ssh");
        Files.createDirectories(sshDir);
        Path readme = sshDir.resolve("README.txt");
        if (!Files.exists(readme)) Files.writeString(readme, """
                SSH 私钥说明
                ============
                1. 把私钥放在本目录，例如 id_ed25519（权限建议 600）。
                2. 在 config.yml 里把 repository.ssh-key-file 指向它（相对 plugins/GitBackup）。
                3. 把对应公钥加到 Git 仓库的部署密钥或账号 SSH Keys。
                4. 首次连接会校验主机指纹；若主机不在 known_hosts 中，连接会失败而不是静默接受。
                本目录内容不会被提交到备份仓库。
                """);
        Path knownHosts = sshDir.resolve("known_hosts");
        if (!Files.exists(knownHosts)) Files.writeString(knownHosts,
                "# 在此写入目标 Git 主机的 known_hosts 条目（由 ssh-keyscan 获取并人工核对指纹）。\n");
    }

    private void writeConfig(List<WorldTarget> discovered) throws IOException {
        StringBuilder sb = new StringBuilder("# GitBackup 配置\n# 相对路径以 plugins/GitBackup 为基准。\n");
        sb.append("repository:\n");
        sb.append("  local-path: backup-repository\n");
        sb.append("  # 备份仓库远程地址（HTTPS 或 SSH）。留空只做本地提交。\n");
        sb.append("  remote-url: \"\"\n");
        sb.append("  branch: main\n");
        sb.append("  https-username: \"\"\n");
        sb.append("  https-token: \"\"\n");
        sb.append("  ssh-key-file: ssh/id_ed25519\n");
        sb.append("  # 出网代理（可选）。支持 http://host:port 与 socks5://host:port。\n");
        sb.append("  # 也可用环境变量 GITBACKUP_PROXY 覆盖。\n");
        sb.append("  proxy: \"\"\n\n");
        sb.append("worlds:\n");
        sb.append("  # 自动发现服务端实际加载的世界；新世界会写入下方列表且默认关闭。\n");
        sb.append("  auto-discover: true\n");
        sb.append("  list:\n");
        Map<String, Boolean> defaults = GitBackupConfig.defaultWorlds(discovered);
        for (Map.Entry<String, Boolean> e : defaults.entrySet()) {
            Optional<WorldTarget> t = discovered.stream().filter(w -> w.key().equals(e.getKey())).findFirst();
            sb.append("    \"").append(e.getKey()).append("\":\n");
            sb.append("      enabled: ").append(e.getValue()).append('\n');
            if (t.isPresent()) sb.append("      # 本地世界: ").append(t.get().name()).append("，目录: ").append(t.get().root().getFileName()).append('\n');
        }
        sb.append("""

                backup:
                  validate: true
                  history-limit: 50
                  # 备份时跳过的世界内路径前缀。玩家数据按约定不备份；
                  # dimensions/ 是因为每个维度会被单独发现为独立世界，重复备份没有必要。
                  # 如需整目录备份，把 dimensions 一项删掉。
                  exclude:
                    - players
                    - playerdata
                    - advancements
                    - stats
                    - fakeplayerdata
                    - resume_fakeplayerdata
                    - dimensions

                schedules:
                  # Quartz 六段：秒 分 时 日 月 星期。worlds 留空 = 全部已启用世界。
                  hourly:
                    cron: "0 0 * * * ?"
                    worlds: []
                  daily:
                    cron: "0 0 4 * * ?"
                    worlds: []

                restore:
                  pre-restore-snapshot: true
                  teleport-players: true
                """);
        Files.writeString(getDataFolder().toPath().resolve("config.yml"), sb.toString());
    }

    // ------------------------------------------------------------------ 世界发现

    List<WorldTarget> discoverTargets() {
        List<WorldTarget> out = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            Path root = world.getWorldFolder().toPath().toAbsolutePath().normalize();
            out.add(new WorldTarget(world.getName(), world.getKey().namespace(), world.getKey().value(), root, true));
        }
        // 配置里显式给出了 path 的世界，即使当前未加载也纳入（例如临时卸载的自定义维度）。
        if (settings != null) {
            for (var e : settings.worlds.entrySet()) {
                if (e.getValue().pathOverride() == null) continue;
                if (out.stream().anyMatch(w -> w.key().equals(e.getKey()))) continue;
                Path p = Path.of(e.getValue().pathOverride());
                if (!p.isAbsolute()) p = Bukkit.getWorldContainer().toPath().resolve(p);
                if (Files.isDirectory(p)) out.add(WorldTarget.offline(e.getKey(), p.toAbsolutePath().normalize()));
            }
        }
        return out;
    }

    Optional<WorldTarget> targetFor(String arg) {
        List<WorldTarget> all = discoverTargets();
        return all.stream().filter(w -> w.name().equalsIgnoreCase(arg) || w.key().equalsIgnoreCase(arg)
                || w.dimension().equalsIgnoreCase(arg)).findFirst();
    }

    Optional<WorldTarget> enabledTarget(String key) {
        if (!settings.enabledWorldKeys().contains(key)) return Optional.empty();
        return discoverTargets().stream().filter(w -> w.key().equals(key)).findFirst();
    }

    // ------------------------------------------------------------------ 备份

    /** 供命令与定时器调用；立刻返回，真正的活在异步线程完成。 */
    CompletableFuture<String> runBackup(String trigger, Set<String> worldKeys, CommandSender sender) {
        CompletableFuture<String> result = new CompletableFuture<>();
        List<WorldTarget> targets = new ArrayList<>();
        for (String key : worldKeys) enabledTarget(key).ifPresent(targets::add);
        if (targets.isEmpty()) {
            result.completeExceptionally(new IllegalStateException("没有可备份的世界（检查 worlds.list 的 enabled）"));
            return result;
        }
        io.execute(() -> {
            if (!busy.compareAndSet(false, true)) {
                result.completeExceptionally(new IllegalStateException("已有备份/恢复任务在执行"));
                return;
            }
            try {
                store.syncWithRemote();
                SnapshotEngine.Result r = snapshots.backup(SnapshotEngine.sorted(targets),
                        buildCommitMessage(trigger, targets), manifestRootFiles(), null);
                if (r.noChanges()) {
                    lastBackupSummary = "无变更（" + Util.TIME.format(Instant.now()) + "）";
                    result.complete(lastBackupSummary);
                } else {
                    String shortId = r.commit().substring(0, 8);
                    lastBackupSummary = shortId + " 对象 " + r.objects() + "（变更 " + r.changed()
                            + "，复用 " + r.reused() + "，写入 " + Util.humanBytes(r.bytes()) + "）" + Util.TIME.format(Instant.now());
                    if (store.hasRemote()) store.push();
                    if (!r.degraded().isEmpty())
                        getLogger().warning("备份存在降级项（这些区块/文件保留上一版本）: " + r.degraded());
                    result.complete(lastBackupSummary);
                }
            } catch (Throwable t) {
                result.completeExceptionally(t);
            } finally {
                busy.set(false);
            }
        });
        return result;
    }

    private String buildCommitMessage(String trigger, List<WorldTarget> targets) {
        String worlds = String.join(",", targets.stream().map(WorldTarget::key).toList());
        return "backup " + Util.TIME.format(Instant.now()) + " worlds=" + worlds + " trigger=" + trigger;
    }

    private Map<String, String> manifestRootFiles() {
        // backup_info.json 只在恢复/回滚时变化；普通备份不重写它，避免每个提交都改动账本。
        return Map.of();
    }

    // ------------------------------------------------------------------ 恢复

    void restore(CommandSender sender, String worldArg, Set<RestoreEngine.Chunk> chunks, String revision) {
        Optional<WorldTarget> target = targetFor(worldArg);
        if (target.isEmpty()) { sender.sendMessage(Text.error("找不到世界: " + worldArg)); return; }
        WorldTarget world = target.get();
        World bukkit = Bukkit.getWorld(world.name());
        if (bukkit == null) {
            sender.sendMessage(Text.error("世界 " + world.name() + " 当前未加载，无法在线上恢复。"));
            sender.sendMessage(Text.note("请先加载该世界，或停服后进入离线模式恢复。"));
            return;
        }
        io.execute(() -> {
            if (!busy.compareAndSet(false, true)) {
                reply(sender, Text.error("已有备份/恢复任务在执行，请稍后再试。"));
                return;
            }
            try {
                RevCommit commit = revision == null || revision.isBlank()
                        ? null : store.resolveCommit(revision);
                if (commit == null) { reply(sender, Text.error("未指定有效提交")); return; }
                reply(sender, Text.note("开始恢复 " + chunks.size() + " 个区块到提交 " + commit.abbreviate(8).name() + " …"));

                String preCommit = null;
                if (settings.preRestoreSnapshot) {
                    SnapshotEngine.Result pre = snapshots.backup(List.of(world),
                            "pre-restore " + Util.TIME.format(Instant.now()) + " of " + commit.abbreviate(8).name(),
                            Map.of(), null);
                    preCommit = pre.commit();
                    if (preCommit != null) reply(sender, Text.ok("恢复前快照已建立: " + preCommit.substring(0, 8)));
                }

                RestoreEngine.Plan plan = restoreEngine.plan(commit, world, chunks);
                if (plan.empty()) { reply(sender, Text.warn("目标版本中没有这些区块的数据，未做任何修改。")); return; }

                if (plan.hotOnly()) {
                    Set<String> failures = applyHot(bukkit, plan);
                    if (!failures.isEmpty()) {
                        reply(sender, Text.warn("以下区块无法热恢复（已加入下次启动生效队列）: " + failures));
                        queuePending(world, commit, chunks);
                    } else {
                        recordRollback(world, commit, preCommit, chunks, "hot");
                        reply(sender, Text.ok("热恢复完成：" + chunks.size() + " 个区块已回到 " + commit.abbreviate(8).name()));
                    }
                } else {
                    queuePending(world, commit, chunks);
                    recordRollback(world, commit, preCommit, chunks, "pending");
                    reply(sender, Text.warn("该恢复需要重建 region 文件（区块不存在/记录变大/槽位为空），已排队在下次启动时应用。"));
                    reply(sender, Text.note("现在执行 /stop 后正常启动，插件会在世界加载前自动完成恢复。"));
                }
                if (preCommit != null && store.hasRemote()) store.push();
            } catch (Throwable t) {
                reply(sender, Text.error("恢复失败: " + rootMessage(t)));
                getLogger().severe("恢复失败: " + t);
            } finally {
                busy.set(false);
            }
        });
    }

    /**
     * 热恢复：先派发到每个区块所属区域线程卸载，再异步写盘。
     *
     * <p>卸载是硬前提：只要区块还在内存里，服务端下一次保存就会把旧数据写回去。
     * 卸载失败（有票据、有玩家、结构未释放）时绝不写入，转而排队到下次启动。
     */
    private Set<String> applyHot(World world, RestoreEngine.Plan plan) throws Exception {
        Set<RestoreEngine.Chunk> chunks = plan.chunks();
        Map<RestoreEngine.Chunk, String> failures = new LinkedHashMap<>();
        for (RestoreEngine.Chunk chunk : chunks) {
            String problem = unloadChunk(world, chunk);
            if (problem != null) failures.put(chunk, problem);
        }
        // 只写成功卸载的区块
        List<RestoreEngine.Write> writes = new ArrayList<>();
        for (RestoreEngine.Write w : plan.hotWrites())
            if (!failures.containsKey(w.chunk())) writes.add(w);
        if (!writes.isEmpty()) {
            Map<Path, Map<Integer, byte[]>> byFile = new LinkedHashMap<>();
            for (RestoreEngine.Write w : writes)
                byFile.computeIfAbsent(w.file(), f -> new LinkedHashMap<>()).put(w.slot(), w.bytes());
            for (var e : byFile.entrySet()) AnvilRegion.replaceInPlace(e.getKey(), e.getValue());
        }
        // 写入期间若区块被重新加载，内存里可能是旧数据，强制丢弃（不保存）让其从磁盘重读。
        for (RestoreEngine.Chunk chunk : chunks) {
            if (failures.containsKey(chunk)) continue;
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            Bukkit.getRegionScheduler().execute(this, world, chunk.x(), chunk.z(), () -> {
                if (world.isChunkLoaded(chunk.x(), chunk.z())) world.unloadChunk(chunk.x(), chunk.z(), false);
                f.complete(!world.isChunkLoaded(chunk.x(), chunk.z()));
            });
            if (!f.get(10, TimeUnit.SECONDS)) failures.put(chunk, "写入后区块仍被加载");
        }
        Set<String> out = new LinkedHashSet<>();
        failures.forEach((c, why) -> out.add(c + "(" + why + ")"));
        return out;
    }

    /** 在区域线程上卸载一个区块；返回 null 表示成功。 */
    private String unloadChunk(World world, RestoreEngine.Chunk chunk) throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            CompletableFuture<String> result = new CompletableFuture<>();
            Bukkit.getRegionScheduler().execute(this, world, chunk.x(), chunk.z(), () -> {
                try {
                    if (!world.isChunkLoaded(chunk.x(), chunk.z())) { result.complete(null); return; }
                    boolean hadPlayers = false;
                    for (Player p : world.getPlayers()) {
                        if ((p.getLocation().getBlockX() >> 4) == chunk.x()
                                && (p.getLocation().getBlockZ() >> 4) == chunk.z() && settings.teleportPlayers) {
                            Location spawn = world.getSpawnLocation();
                            p.teleportAsync(spawn);
                            hadPlayers = true;
                        }
                    }
                    if (hadPlayers) { result.complete("区域内玩家正在被传送"); return; }
                    boolean ok = world.unloadChunk(chunk.x(), chunk.z(), true);
                    if (!ok) { result.complete("服务端拒绝卸载（可能有票据或被实体占用）"); return; }
                    result.complete(world.isChunkLoaded(chunk.x(), chunk.z()) ? "卸载后仍处于加载状态" : null);
                } catch (Throwable t) {
                    result.complete("卸载异常: " + rootMessage(t));
                }
            });
            String problem = result.get(15, TimeUnit.SECONDS);
            if (problem == null) return null;
            if (!problem.contains("正在被传送")) return problem;
            Thread.sleep(500);
        }
        return "区块无法卸载";
    }

    // ------------------------------------------------------------------ 待生效队列 / 回滚

    private Path pendingDir() { return getDataFolder().toPath().resolve("pending"); }

    private void queuePending(WorldTarget world, RevCommit commit, Set<RestoreEngine.Chunk> chunks) throws IOException {
        Files.createDirectories(pendingDir());
        Path file = pendingDir().resolve(System.currentTimeMillis() + "-" + world.dimension() + ".json");
        StringBuilder sb = new StringBuilder();
        sb.append("{\"world\":\"").append(world.key()).append("\",\"root\":\"")
                .append(world.root().toString().replace("\\", "\\\\")).append("\",\"commit\":\"")
                .append(commit.name()).append("\",\"chunks\":[");
        boolean first = true;
        for (RestoreEngine.Chunk c : chunks) {
            if (!first) sb.append(',');
            first = false;
            sb.append("[").append(c.x()).append(',').append(c.z()).append(']');
        }
        sb.append("],\"time\":\"").append(Instant.now()).append("\"}");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /** 在 onLoad 阶段（世界未加载）应用所有排队恢复。 */
    private void applyPendingRestores() {
        Path dir = getDataFolder().toPath().resolve("pending");
        if (!Files.isDirectory(dir)) return;
        List<Path> jobs;
        try (Stream<Path> s = Files.list(dir)) {
            jobs = s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            getLogger().severe("无法读取待生效恢复队列: " + e.getMessage());
            return;
        }
        if (jobs.isEmpty()) return;
        GitBackupConfig cfg = loadConfigForOnLoad();
        if (cfg == null) return;
        getLogger().info("检测到 " + jobs.size() + " 个待生效恢复任务，开始在世界加载前应用…");
        try (BackupStore local = new BackupStore(cfg.repositoryPath, cfg.branch, cfg.remoteUrl,
                getDataFolder().toPath().resolve("ssh"), cfg.sshKey, cfg.httpsUser, cfg.httpsToken, cfg.proxy)) {
            RestoreEngine engine = new RestoreEngine(local);
            com.google.gson.JsonParser parser = null;
            for (Path job : jobs) {
                try {
                    var json = com.google.gson.JsonParser.parseString(Files.readString(job, StandardCharsets.UTF_8)).getAsJsonObject();
                    WorldTarget target = WorldTarget.offline(json.get("world").getAsString(), Path.of(json.get("root").getAsString()));
                    RevCommit commit = local.resolveCommit(json.get("commit").getAsString());
                    Set<RestoreEngine.Chunk> chunks = new LinkedHashSet<>();
                    for (var el : json.getAsJsonArray("chunks"))
                        chunks.add(new RestoreEngine.Chunk(el.getAsJsonArray().get(0).getAsInt(), el.getAsJsonArray().get(1).getAsInt()));
                    RestoreEngine.Plan plan = engine.plan(commit, target, chunks);
                    int n = engine.applyCold(plan);
                    Files.deleteIfExists(job);
                    getLogger().info("已完成待生效恢复 " + job.getFileName() + "（重建 region 文件 " + n + " 个）");
                } catch (Throwable t) {
                    getLogger().severe("待生效恢复失败，任务已保留: " + job.getFileName() + " → " + t);
                }
            }
        } catch (Throwable t) {
            getLogger().severe("打开备份仓库失败，待生效恢复已跳过: " + t);
        }
    }

    private GitBackupConfig loadConfigForOnLoad() {
        Path file = getDataFolder().toPath().resolve("config.yml");
        if (!Files.exists(file)) return null;
        try {
            org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            return GitBackupConfig.load(yaml, getDataFolder().toPath());
        } catch (Exception e) {
            getLogger().severe("配置无法解析: " + e.getMessage());
            return null;
        }
    }

    private void recordRollback(WorldTarget world, RevCommit commit, String preCommit,
                                Set<RestoreEngine.Chunk> chunks, String mode) throws Exception {
        BackupManifest manifest = readManifest();
        BackupManifest.RollbackEntry e = new BackupManifest.RollbackEntry();
        e.id = "rb-" + System.currentTimeMillis();
        e.world = world.key();
        e.commit = commit.name();
        e.preCommit = preCommit;
        e.time = Instant.now().toString();
        e.chunks = chunks.size();
        e.applied = "hot".equals(mode);
        e.rolledBack = false;
        e.note = mode;
        StringBuilder list = new StringBuilder();
        for (RestoreEngine.Chunk c : chunks) list.append(list.length() > 0 ? ";" : "").append(c.x()).append(',').append(c.z());
        e.note = mode + "|" + list;
        manifest.addRollback(e);
        commitManifest(manifest, "restore " + world.key() + " " + chunks.size() + " chunks → " + commit.abbreviate(8).name());
    }

    void rollback(CommandSender sender, String id) {
        io.execute(() -> {
            try {
                BackupManifest manifest = readManifest();
                BackupManifest.RollbackEntry entry = manifest.findRollback(id);
                if (entry == null) { reply(sender, Text.error("找不到回滚记录: " + id)); return; }
                if (entry.preCommit == null) { reply(sender, Text.error("该记录没有恢复前快照，无法回滚")); return; }
                Set<RestoreEngine.Chunk> chunks = parseChunks(entry.note);
                Optional<WorldTarget> target = targetFor(entry.world);
                if (target.isEmpty()) { reply(sender, Text.error("世界不存在: " + entry.world)); return; }
                World bukkit = Bukkit.getWorld(target.get().name());
                if (bukkit == null) { reply(sender, Text.error("世界未加载，无法在线回滚")); return; }
                RevCommit pre = store.resolveCommit(entry.preCommit);
                RestoreEngine.Plan plan = restoreEngine.plan(pre, target.get(), chunks);
                if (plan.hotOnly()) {
                    Set<String> failures = applyHot(bukkit, plan);
                    if (failures.isEmpty()) {
                        entry.rolledBack = true;
                        commitManifest(manifest, "rollback " + entry.id);
                        reply(sender, Text.ok("已回滚 " + entry.id + "：" + chunks.size() + " 个区块恢复到恢复前状态"));
                    } else {
                        queuePending(target.get(), pre, chunks);
                        entry.rolledBack = true;
                        commitManifest(manifest, "rollback queued " + entry.id);
                        reply(sender, Text.warn("部分区块需重启应用，已排队: " + failures));
                    }
                } else {
                    queuePending(target.get(), pre, chunks);
                    entry.rolledBack = true;
                    commitManifest(manifest, "rollback queued " + entry.id);
                    reply(sender, Text.warn("回滚需要重建 region 文件，已排队在下次启动应用。"));
                }
            } catch (Throwable t) {
                reply(sender, Text.error("回滚失败: " + rootMessage(t)));
                getLogger().severe("回滚失败: " + t);
            }
        });
    }

    Set<RestoreEngine.Chunk> parseChunksPublic(String note) { return parseChunks(note); }

    private Set<RestoreEngine.Chunk> parseChunks(String note) {
        Set<RestoreEngine.Chunk> out = new LinkedHashSet<>();
        if (note == null) return out;
        int bar = note.indexOf('|');
        if (bar < 0) return out;
        for (String part : note.substring(bar + 1).split(";"))
            if (!part.isBlank()) out.add(RestoreEngine.Chunk.parse(part));
        return out;
    }

    // ------------------------------------------------------------------ 账本

    BackupManifest readManifest() throws IOException {
        RevCommit head = store.head();
        byte[] data = head == null ? null : store.readBlob(head, BackupStore.INFO_FILE);
        return BackupManifest.parse(data);
    }

    private void commitManifest(BackupManifest manifest, String message) throws Exception {
        // 提交树是"完整视图"：必须以父提交为基线，否则会删掉整个备份目录
        BackupStore.Tree tree = new BackupStore.Tree();
        tree.putAll(store.parentBlobs());
        tree.put(BackupStore.VERSION_FILE, store.insertBlob(BackupStore.FORMAT.getBytes(StandardCharsets.UTF_8)));
        store.commit(tree, Set.of(), message, Map.of(BackupStore.INFO_FILE, manifest.toJson()));
        if (store.hasRemote()) store.push();
    }

    // ------------------------------------------------------------------ 工具

    void reply(CommandSender sender, Component message) {
        Bukkit.getGlobalRegionScheduler().execute(this, () -> sender.sendMessage(message));
    }

    void replyNow(CommandSender sender, Component message) {
        sender.sendMessage(message);
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /** 所有 Git 访问都必须走这个单线程，避免 ObjectInserter 被多线程同时使用。 */
    <T> CompletableFuture<T> runIo(java.util.concurrent.Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        io.execute(() -> {
            try { future.complete(task.call()); }
            catch (Throwable t) { future.completeExceptionally(t); }
        });
        return future;
    }

    GitBackupConfig settings() { return settings; }
    BackupStore store() { return store; }
    RestoreEngine restoreEngine() { return restoreEngine; }
    ScheduleRunner scheduler() { return scheduler; }
    boolean isBusy() { return busy.get(); }
    String lastBackupSummary() { return lastBackupSummary; }
}
