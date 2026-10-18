package cn.qzgeek.gitbackup;

import org.eclipse.jgit.revwalk.RevCommit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 离线命令行。与插件共用同一套引擎，用于停服恢复、仓库巡检和自动化测试。
 *
 * <pre>
 *   backup    --world &lt;key&gt;=&lt;目录&gt; [...] [--validate false] [--message ...]
 *   list
 *   show      --commit &lt;hash&gt; [--world &lt;key&gt;]
 *   plan      --world &lt;key&gt;=&lt;目录&gt; --commit &lt;hash&gt; --chunks x,z[;x,z]
 *   restore   --world &lt;key&gt;=&lt;目录&gt; --commit &lt;hash&gt; --chunks x,z[;x,z] [--allow-loaded]
 *   pending   --dir &lt;待生效目录&gt;
 *   cron      &lt;表达式&gt;
 * </pre>
 */
public final class OfflineMain {

    public static void main(String[] argv) {
        try {
            System.exit(run(argv));
        } catch (Throwable t) {
            System.err.println("失败: " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static int run(String[] argv) throws Exception {
        if (argv.length < 2) { usage(); return 2; }
        String command = argv[0];
        Map<String, String> opts = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < argv.length; i++) {
            if (argv[i].startsWith("--") && i + 1 < argv.length) { opts.put(argv[i].substring(2), argv[++i]); }
            else positional.add(argv[i]);
        }
        if (positional.isEmpty()) { usage(); return 2; }
        Path repoDir = Path.of(positional.get(0)).toAbsolutePath().normalize();

        if ("cron".equals(command)) {
            String err = ScheduleRunner.validate(positional.get(0));
            System.out.println(err == null ? "OK" : "INVALID " + err);
            return err == null ? 0 : 1;
        }

        String branch = opts.getOrDefault("branch", "main");
        String remote = opts.getOrDefault("remote", "");
        try (BackupStore store = new BackupStore(repoDir, branch, remote, null, null, null, null,
                opts.getOrDefault("proxy", ""))) {
            return switch (command) {
                case "list" -> list(store);
                case "show" -> show(store, opts);
                case "backup" -> backup(store, opts);
                case "plan" -> plan(store, opts);
                case "restore" -> restore(store, opts, false);
                case "pending" -> pending(store, opts);
                default -> { usage(); yield 2; }
            };
        }
    }

    private static int list(BackupStore store) throws Exception {
        List<BackupStore.CommitInfo> history = store.history(100);
        if (history.isEmpty()) System.out.println("（空仓库）");
        for (BackupStore.CommitInfo c : history)
            System.out.printf("%s  %s  %s%n", c.id().substring(0, 8), Util.TIME.format(c.time()), c.message());
        return 0;
    }

    private static int show(BackupStore store, Map<String, String> opts) throws Exception {
        RevCommit commit = store.resolveCommit(opts.get("commit"));
        String world = opts.get("world");
        String prefix = world == null ? "data/" : WorldTarget.offline(world, Path.of(".")).repoPath() + "/";
        try (var tw = new org.eclipse.jgit.treewalk.TreeWalk(store.repository())) {
            tw.setRecursive(true);
            tw.addTree(commit.getTree());
            int n = 0;
            while (tw.next()) {
                String path = tw.getPathString();
                if (!path.startsWith(prefix)) continue;
                if (n++ < 40) System.out.printf("%-90s %s%n", path, tw.getObjectId(0).name());
            }
            System.out.println("总共 " + n + " 个对象");
        }
        String info = null;
        byte[] data = store.readBlob(commit, BackupStore.INFO_FILE);
        if (data != null) info = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        if (info != null) System.out.println("--- backup_info.json ---\n" + info);
        return 0;
    }

    private static int backup(BackupStore store, Map<String, String> opts) throws Exception {
        List<WorldTarget> targets = parseWorlds(opts.get("world"));
        if (targets.isEmpty()) throw new IllegalArgumentException("需要 --world <key>=<目录>");
        boolean validate = !"false".equalsIgnoreCase(opts.getOrDefault("validate", "true"));
        store.syncWithRemote();
        SnapshotIndex index = SnapshotIndex.load(Path.of(opts.getOrDefault("index", ".gitbackup-index.json")));
        SnapshotEngine engine = new SnapshotEngine(store, index, validate);
        String message = opts.getOrDefault("message", "offline backup " + Util.TIME.format(java.time.Instant.now()));
        SnapshotEngine.Result r = engine.backup(targets, message, Map.of(), null);
        if (r.noChanges()) {
            System.out.println("NOCHANGES objects=" + r.objects());
        } else {
            System.out.println("COMMIT " + r.commit());
            System.out.println("objects=" + r.objects() + " changed=" + r.changed() + " reused=" + r.reused()
                    + " bytes=" + r.bytes() + " degraded=" + r.degraded());
        }
        if (!store.hasRemote() && opts.containsKey("push")) throw new IllegalArgumentException("没有远端可推送");
        if (opts.containsKey("push")) { store.push(); System.out.println("PUSHED"); }
        return 0;
    }

    private static int plan(BackupStore store, Map<String, String> opts) throws Exception {
        Reserved r = prepare(store, opts);
        RestoreEngine.Plan plan = new RestoreEngine(store).plan(r.commit, r.world, r.chunks);
        System.out.println("hotWrites=" + plan.hotWrites().size() + " coldChanges=" + plan.coldChanges().size()
                + " extras=" + plan.extras().size() + " hotOnly=" + plan.hotOnly());
        for (var c : plan.coldChanges()) System.out.println("  COLD " + c.file() + " : " + c.reason());
        for (var n : plan.notes()) System.out.println("  NOTE " + n);
        return 0;
    }

    private static int restore(BackupStore store, Map<String, String> opts, boolean unused) throws Exception {
        Reserved r = prepare(store, opts);
        RestoreEngine engine = new RestoreEngine(store);
        RestoreEngine.Plan plan = engine.plan(r.commit, r.world, r.chunks);
        int files = plan.hotOnly() ? engine.applyHot(plan) : engine.applyCold(plan);
        System.out.println("RESTORED files=" + files + " mode=" + (plan.hotOnly() ? "hot" : "cold")
                + " chunks=" + r.chunks.size());
        return 0;
    }

    private static int pending(BackupStore store, Map<String, String> opts) throws Exception {
        Path dir = Path.of(opts.get("dir"));
        RestoreEngine engine = new RestoreEngine(store);
        int applied = 0;
        try (var s = Files.list(dir)) {
            for (Path job : s.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                var json = com.google.gson.JsonParser.parseString(Files.readString(job)).getAsJsonObject();
                WorldTarget target = WorldTarget.offline(json.get("world").getAsString(), Path.of(json.get("root").getAsString()));
                RevCommit commit = store.resolveCommit(json.get("commit").getAsString());
                Set<RestoreEngine.Chunk> chunks = new LinkedHashSet<>();
                for (var el : json.getAsJsonArray("chunks"))
                    chunks.add(new RestoreEngine.Chunk(el.getAsJsonArray().get(0).getAsInt(), el.getAsJsonArray().get(1).getAsInt()));
                engine.applyCold(engine.plan(commit, target, chunks));
                Files.delete(job);
                applied++;
            }
        }
        System.out.println("PENDING_APPLIED " + applied);
        return 0;
    }

    private record Reserved(WorldTarget world, RevCommit commit, Set<RestoreEngine.Chunk> chunks) {}

    private static Reserved prepare(BackupStore store, Map<String, String> opts) throws Exception {
        List<WorldTarget> worlds = parseWorlds(opts.get("world"));
        if (worlds.size() != 1) throw new IllegalArgumentException("需要恰好一个 --world <key>=<目录>");
        RevCommit commit = store.resolveCommit(opts.get("commit"));
        String chunksArg = opts.get("chunks");
        if (chunksArg == null) throw new IllegalArgumentException("需要 --chunks x,z[;x,z]");
        Set<RestoreEngine.Chunk> chunks = new LinkedHashSet<>();
        for (String part : chunksArg.split("[; ]+")) if (!part.isBlank()) chunks.add(RestoreEngine.Chunk.parse(part));
        for (WorldTarget w : worlds) {
            if (Files.exists(w.root().resolve("session.lock")) && !"true".equalsIgnoreCase(opts.getOrDefault("allow-loaded", "false")))
                throw new IllegalStateException("世界似乎仍在运行（存在 session.lock）: " + w.root());
        }
        return new Reserved(worlds.get(0), commit, chunks);
    }

    private static List<WorldTarget> parseWorlds(String value) {
        List<WorldTarget> out = new ArrayList<>();
        if (value == null) return out;
        for (String spec : value.split(";")) {
            int eq = spec.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("--world 需要 key=目录 格式: " + spec);
            out.add(WorldTarget.offline(spec.substring(0, eq).trim(),
                    Path.of(spec.substring(eq + 1).trim()).toAbsolutePath().normalize()));
        }
        return out;
    }

    private static void usage() {
        System.err.println("""
                用法: GitBackup <命令> <仓库目录> [选项]
                  backup    --world <key>=<目录>[;<key>=<目录>] [--validate true|false] [--push] [--index <文件>]
                  list
                  show      --commit <hash> [--world <key>]
                  plan      --world <key>=<目录> --commit <hash> --chunks x,z[;x,z]
                  restore   --world <key>=<目录> --commit <hash> --chunks x,z[;x,z] [--allow-loaded true]
                  pending   --dir <待生效目录>
                  cron      <六段表达式>
                """);
    }
}
