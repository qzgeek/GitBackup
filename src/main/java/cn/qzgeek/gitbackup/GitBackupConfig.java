package cn.qzgeek.gitbackup;

import com.google.gson.GsonBuilder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 插件配置。世界标识全部来自运行时实际值；配置只是"开关 + 覆盖"。 */
final class GitBackupConfig {
    static final List<String> VANILLA_DEFAULTS = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");

    Path repositoryPath;
    String remoteUrl;
    String branch;
    Path sshKey;
    String httpsUser;
    String proxy;
    String httpsToken;
    boolean autoDiscover;
    boolean validate;
    List<String> excludes = new java.util.ArrayList<>();
    boolean teleportPlayers;
    boolean preRestoreSnapshot;
    int historyLimit;
    final Map<String, WorldSetting> worlds = new LinkedHashMap<>();
    final List<Schedule> schedules = new ArrayList<>();
    final Map<String, String> pathOverrides = new LinkedHashMap<>();

    record WorldSetting(boolean enabled, String pathOverride) {}
    record Schedule(String id, String cron, List<String> worlds) {}

    static GitBackupConfig load(FileConfiguration c, Path dataFolder) {
        GitBackupConfig cfg = new GitBackupConfig();
        String repo = c.getString("repository.local-path", "backup-repository");
        Path rp = Path.of(repo);
        cfg.repositoryPath = rp.isAbsolute() ? rp : dataFolder.resolve(rp);
        cfg.remoteUrl = c.getString("repository.remote-url", "");
        cfg.branch = c.getString("repository.branch", "main");
        cfg.httpsUser = c.getString("repository.https-username", "");
        cfg.proxy = c.getString("repository.proxy", "");
        cfg.httpsToken = c.getString("repository.https-token", "");
        String key = c.getString("repository.ssh-key-file", "ssh/id_ed25519");
        cfg.sshKey = key == null || key.isBlank() ? null
                : (Path.of(key).isAbsolute() ? Path.of(key) : dataFolder.resolve(key));
        cfg.autoDiscover = c.getBoolean("worlds.auto-discover", true);
        cfg.validate = c.getBoolean("backup.validate", true);
        List<String> excludes = c.getStringList("backup.exclude");
        cfg.excludes = excludes == null || excludes.isEmpty()
                ? new java.util.ArrayList<>(SnapshotEngine.DEFAULT_EXCLUDES) : new java.util.ArrayList<>(excludes);
        cfg.teleportPlayers = c.getBoolean("restore.teleport-players", true);
        cfg.preRestoreSnapshot = c.getBoolean("restore.pre-restore-snapshot", true);
        cfg.historyLimit = Math.max(5, c.getInt("backup.history-limit", 50));

        ConfigurationSection ws = c.getConfigurationSection("worlds.list");
        if (ws != null) {
            for (String worldKey : ws.getKeys(false)) {
                cfg.worlds.put(worldKey, new WorldSetting(
                        ws.getBoolean(worldKey + ".enabled", false),
                        ws.getString(worldKey + ".path", null)));
            }
        }
        ConfigurationSection ss = c.getConfigurationSection("schedules");
        if (ss != null) {
            for (String id : ss.getKeys(false)) {
                String cron = ss.getString(id + ".cron", "0 0 * * * ?");
                List<String> list = ss.getStringList(id + ".worlds");
                cfg.schedules.add(new Schedule(id, cron, list == null ? List.of() : list));
            }
        }
        return cfg;
    }

    Set<String> enabledWorldKeys() {
        Set<String> out = new LinkedHashSet<>();
        for (var e : worlds.entrySet()) if (e.getValue().enabled()) out.add(e.getKey());
        return out;
    }

    /** 把运行时发现到、配置里还没有的世界补进去（默认关闭，避免意外备份）。返回是否发生变更。 */
    boolean mergeDiscovered(List<WorldTarget> discovered) {
        boolean changed = false;
        for (WorldTarget w : discovered) if (!worlds.containsKey(w.key())) {
            worlds.put(w.key(), new WorldSetting(false, null));
            changed = true;
        }
        return changed;
    }

    /** 首次生成配置时使用：原版三个维度默认开启，其余默认关闭。 */
    static Map<String, Boolean> defaultWorlds(List<WorldTarget> discovered) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (WorldTarget w : discovered) out.put(w.key(), VANILLA_DEFAULTS.contains(w.key()));
        for (String v : VANILLA_DEFAULTS) out.putIfAbsent(v, true);
        return out;
    }

    String toYaml(List<WorldTarget> discovered) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                # GitBackup 配置
                # 相对路径均以 plugins/GitBackup 为基准。
                repository:
                  local-path: backup-repository
                """);
        sb.append("  # 远程仓库，支持 https:// 与 ssh://（也可写 git@host:path）。留空则只做本地提交。\n");
        sb.append("  remote-url: \"").append(escape(remoteUrl)).append("\"\n");
        sb.append("  branch: ").append(branch).append('\n');
        sb.append("""
                  # HTTPS：只需令牌（GitHub fine-grained token 或 classic token）。
                  # 也可以不改配置，改用环境变量 GITBACKUP_HTTPS_TOKEN / GITBACKUP_HTTPS_USERNAME。
                  https-username: ""
                  https-token: ""
                  # SSH：把私钥放到 plugins/GitBackup/ssh/ 下并在此填写相对路径。
                  ssh-key-file: ssh/id_ed25519

                worlds:
                  # true：自动把服务端实际加载的世界补进下面的列表（补入项默认关闭，不会自动备份）。
                  auto-discover: true
                  list:
                """);
        List<WorldTarget> known = new ArrayList<>(discovered);
        for (String key : worlds.keySet())
            if (known.stream().noneMatch(w -> w.key().equals(key)))
                known.add(WorldTarget.offline(key, Path.of(".")));
        for (WorldTarget w : known) {
            WorldSetting s = worlds.getOrDefault(w.key(), new WorldSetting(false, null));
            sb.append("    \"").append(w.key()).append("\":\n");
            sb.append("      enabled: ").append(s.enabled()).append('\n');
            if (w.root() != null && !w.name().equals(w.key()))
                sb.append("      # 本地世界名: ").append(w.name()).append('\n');
            if (s.pathOverride() != null) sb.append("      path: ").append(s.pathOverride()).append('\n');
        }
        sb.append("""

                backup:
                  # 备份时校验每条区块记录的完整性与 xPos/zPos 自洽性（建议开启）。
                  validate: true
                  # 最近一次 list 展示的提交数量。
                  history-limit: 50

                schedules:
                  # Quartz 六段表达式：秒 分 时 日 月 星期
                  # worlds 留空表示备份所有 enabled 的世界。
                """);
        if (schedules.isEmpty()) {
            sb.append("  hourly:\n    cron: \"0 0 * * * ?\"\n    worlds: []\n");
            sb.append("  daily:\n    cron: \"0 0 4 * * ?\"\n    worlds: []\n");
        } else {
            for (Schedule s : schedules) {
                sb.append("  ").append(s.id()).append(":\n");
                sb.append("    cron: \"").append(s.cron()).append("\"\n");
                sb.append("    worlds: ").append(s.worlds().isEmpty() ? "[]" : new GsonBuilder().create().toJson(s.worlds())).append('\n');
            }
        }
        sb.append("""

                restore:
                  # 恢复前自动对目标世界做一次快照（回滚依赖它，强烈建议保持开启）。
                  pre-restore-snapshot: true
                  # 恢复时把目标区块内的玩家传送到该世界出生点。
                  teleport-players: true
                """);
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
