package cn.qzgeek.gitbackup;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectDatabase;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 备份仓库（裸仓库）访问层。
 *
 * <p>刻意不使用工作区：备份内容是"对象库 + 提交历史"，把上万个区块对象铺到工作区只会浪费 IO
 * 且让 {@code git status} 失去意义。所有写入走 ObjectInserter → TreeFormatter → CommitBuilder，
 * 与 Git 对象模型完全一致，远端克隆后即可按目录结构浏览。
 */
final class BackupStore implements AutoCloseable {
    static final String FORMAT = "GitBackup format 1\n";
    static final String VERSION_FILE = "version.txt";
    static final String INFO_FILE = "backup_info.json";

    private final Path dir;
    private final Git git;
    private final Repository repo;
    private final String branch;
    private final CredentialsProvider credentials;
    private final SshdSessionFactory ssh;
    private final String remoteUrl;
    private final ProxySupport proxy;
    private ObjectInserter inserter;
    private final Map<String, ObjectId> parentBlobs = new HashMap<>();
    private boolean parentBlobsLoaded;

    BackupStore(Path dir, String branch, String remoteUrl, Path sshDir, Path identity,
                String httpsUser, String httpsToken) throws Exception {
        this(dir, branch, remoteUrl, sshDir, identity, httpsUser, httpsToken, null);
    }

    BackupStore(Path dir, String branch, String remoteUrl, Path sshDir, Path identity,
                String httpsUser, String httpsToken, String proxySpec) throws Exception {
        this.dir = dir.toAbsolutePath().normalize();
        this.branch = branch;
        this.remoteUrl = remoteUrl == null ? "" : remoteUrl.trim();
        String proxyValue = proxySpec == null || proxySpec.isBlank()
                ? System.getenv().getOrDefault("GITBACKUP_PROXY", "") : proxySpec;
        this.proxy = ProxySupport.parse(proxyValue);
        if (!Repository.isValidRefName(Constants.R_HEADS + branch)) throw new IOException("非法分支名: " + branch);

        // 支持两种布局：插件自建的裸仓库，以及人工 git clone 出来的普通仓库（灾后重建场景）。
        if ((Files.exists(this.dir.resolve("config")) && Files.isDirectory(this.dir.resolve("objects")))
                || Files.isDirectory(this.dir.resolve(".git"))) {
            this.git = Git.open(this.dir.toFile());
        } else if (Files.exists(this.dir) && Files.list(this.dir).findAny().isPresent()) {
            throw new IOException("备份仓库目录非空且不是 Git 仓库: " + this.dir);
        } else {
            Files.createDirectories(this.dir);
            this.git = Git.init().setBare(true).setInitialBranch(branch).setDirectory(this.dir.toFile()).call();
        }
        this.repo = this.git.getRepository();

        if (!remoteUrl.isBlank()) {
            URIish uri = new URIish(remoteUrl);
            if (uri.getPass() != null) throw new IOException("远程 URL 禁止内嵌密码/令牌，请使用配置或环境变量");
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean ssh = "ssh".equalsIgnoreCase(uri.getScheme())
                    || ("git@".startsWith(uri.getScheme() == null ? "" : uri.getScheme()) && uri.getScheme() == null);
            if (!https && !ssh) throw new IOException("远程只支持 https:// 或 ssh://（或 git@host:path）");
            var cfg = repo.getConfig();
            cfg.setString("remote", "origin", "url", remoteUrl);
            cfg.setStringList("remote", "origin", "fetch", List.of("+refs/heads/*:refs/remotes/origin/*"));
            cfg.setString("core", null, "logAllRefUpdates", "true");
            cfg.save();
        }

        String token = httpsToken != null && !httpsToken.isBlank() ? httpsToken
                : System.getenv().getOrDefault("GITBACKUP_HTTPS_TOKEN", "");
        String user = httpsUser != null && !httpsUser.isBlank() ? httpsUser
                : System.getenv().getOrDefault("GITBACKUP_HTTPS_USERNAME", "x-access-token");
        this.credentials = token.isBlank() ? null : new UsernamePasswordCredentialsProvider(user, token);

        Path sshHome = sshDir == null ? Path.of(System.getProperty("user.home")) : sshDir;
        Files.createDirectories(sshHome);
        Path strictConfig = sshHome.resolve("gitbackup-ssh-config");
        if (!Files.exists(strictConfig)) {
            // 不使用用户 ~/.ssh/config，避免其中 StrictHostKeyChecking=no 削弱主机校验。
            Files.writeString(strictConfig, "Host *\n  StrictHostKeyChecking yes\n");
        }
        Path keyFile = identity;
        this.ssh = new SshdSessionFactoryBuilder()
                .setHomeDirectory(Path.of(System.getProperty("user.home")).toFile())
                .setSshDirectory(sshHome.toFile())
                .setConfigFile(d -> strictConfig.toFile())
                .setDefaultIdentities(d -> keyFile == null
                        ? List.of(d.toPath().resolve("id_ed25519"), d.toPath().resolve("id_rsa"))
                        : List.of(keyFile))
                .build(null);
    }

    Path dir() { return dir; }
    String branch() { return branch; }
    String remoteUrl() { return remoteUrl; }
    boolean hasRemote() { return !remoteUrl.isBlank(); }
    Repository repository() { return repo; }

    // ---------------------------------------------------------------- 读取

    RevCommit head() throws IOException {
        ObjectId id = repo.resolve(Constants.R_HEADS + branch);
        if (id == null) return null;
        try (RevWalk w = new RevWalk(repo)) { return w.parseCommit(id); }
    }

    /** 父提交中"路径 → blob"的映射，用于判定对象是否真的未变更。 */
    Map<String, ObjectId> parentBlobs() throws IOException {
        if (parentBlobsLoaded) return parentBlobs;
        parentBlobsLoaded = true;
        RevCommit parent = head();
        if (parent == null) return parentBlobs;
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.setRecursive(true);
            tw.addTree(parent.getTree());
            while (tw.next()) parentBlobs.put(tw.getPathString(), tw.getObjectId(0));
        }
        return parentBlobs;
    }

    /** 立即把 blob 写入对象库（已存在则不重复写），内存占用与单个对象大小相关，不随世界规模增长。 */
    ObjectId insertBlob(byte[] data) throws IOException {
        return inserter().insert(Constants.OBJ_BLOB, data);
    }

    /**
     * 惰性创建对象写入器。
     *
     * <p>默认的 loose-object 写入器每个对象要建一个文件 + rename：在 13 GiB 真实存档上实测只有
     * 约 165 个对象/秒（近 300 万个区块对象需要几小时），完全不可用。因此优先使用 JGit 的
     * {@code PackInserter}，把对象顺序写进单个 pack 文件（实测快两个数量级）。
     *
     * <p>{@code PackInserter} 位于 internal 包，构造器是包可见的，这里通过反射构造；
     * 一旦失败（不同 JGit 版本）就退回默认写入器，功能不受影响，只是慢。
     */
    private ObjectInserter inserter() throws IOException {
        if (inserter == null) {
            inserter = createPackInserter();
        }
        return inserter;
    }

    private ObjectInserter createPackInserter() throws IOException {
        preferNativeSha1();
        ObjectDatabase od = repo.getObjectDatabase();
        try {
            // 直接引用类常量而不是字符串：构建时的包重定位（shadow）会同步改写引用，
            // 字符串里写死的类名在重定位后反而找不到。
            Class<?> type = org.eclipse.jgit.internal.storage.file.PackInserter.class;
            Object candidate = null;
            for (var ctor : type.getDeclaredConstructors()) {
                if (ctor.getParameterCount() != 1) continue;
                if (!ctor.getParameterTypes()[0].isInstance(od)) continue;
                ctor.setAccessible(true);
                candidate = ctor.newInstance(od);
                break;
            }
            if (candidate instanceof ObjectInserter packInserter) {
                // 区块记录本身已经是 zlib 压缩过的，再压缩一次纯属浪费 CPU（实测拖慢两个数量级），
                // 因此按"存储"级别写入 pack：体积几乎不变，速度接近磁盘带宽。
                try {
                    type.getMethod("setCompressionLevel", int.class).invoke(packInserter, 0);
                } catch (ReflectiveOperationException ignored) {
                    // 老版本没有该方法时忽略
                }
                return packInserter;
            }
        } catch (Throwable ignored) {
            // 反射不可用（模块化/版本差异）时退回默认写入器
        }
        return repo.newObjectInserter();
    }

    /**
     * 让 JGit 用 JDK 的 SHA-1（硬件指令加速），而不是它自带的纯 Java 实现。
     *
     * <p>实测：13 GiB 真实存档、约 300 万个区块对象时，纯 Java SHA-1 只有约 15 MB/s，
     * 单线程 100% CPU 卡在 {@code SHA1Java.compress}，整次备份要数小时；JDK 的 SHA-1
     * 走 SHA-NI，能到 GB/s 量级。这是整个备份流程里唯一值得动 JGit 内部实现的地方。
     */
    private static volatile boolean sha1Configured;

    private static void preferNativeSha1() {
        if (sha1Configured) return;
        sha1Configured = true;
        try {
            Class<?> sha1 = org.eclipse.jgit.util.sha1.SHA1.class;
            Class<?> impl = org.eclipse.jgit.util.sha1.SHA1.Sha1Implementation.class;
            Object nativeImpl = Enum.valueOf((Class) impl, "JDKNATIVE");
            for (java.lang.reflect.Field f : sha1.getDeclaredFields()) {
                if (f.getType() != impl || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                f.set(null, nativeImpl);
            }
        } catch (Throwable ignored) {
            // 失败时继续使用 JGit 自带实现：结果正确，只是慢
        }
    }

    boolean hasObject(ObjectId id) throws IOException {
        try { return repo.getObjectDatabase().has(id); } catch (IOException e) { return false; }
    }

    static ObjectId blobId(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            md.update(("blob " + data.length + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            md.update(data);
            return ObjectId.fromRaw(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    String headId() throws IOException {
        RevCommit c = head();
        return c == null ? "" : c.name();
    }

    RevCommit resolveCommit(String revision) throws IOException {
        if (revision == null || revision.isBlank()) throw new IOException("未指定提交");
        String spec = revision.trim();
        if (!spec.matches("[0-9a-fA-F]{4,40}")) throw new IOException("只接受提交哈希，不接受分支或 HEAD 等可变引用: " + spec);
        ObjectId id = repo.resolve(spec);
        if (id == null) throw new IOException("仓库中不存在该提交: " + spec);
        try (RevWalk w = new RevWalk(repo)) {
            try { return w.parseCommit(id); } catch (Exception e) { throw new IOException("该对象不是提交: " + spec); }
        }
    }

    byte[] readBlob(RevCommit commit, String path) throws IOException {
        try (TreeWalk tw = TreeWalk.forPath(repo, path, commit.getTree())) {
            if (tw == null) return null;
            FileMode mode = tw.getFileMode(0);
            if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE)
                throw new IOException("拒绝非普通文件对象: " + path);
            ObjectLoader loader = repo.open(tw.getObjectId(0));
            long size = loader.getSize();
            if (size > AnvilRegion.MAX_RECORD_BYTES) throw new IOException("对象过大，拒绝读取: " + path);
            return loader.getBytes((int) size + 1);
        }
    }

    byte[] readBlob(String revision, String path) throws IOException {
        return readBlob(resolveCommit(revision), path);
    }

    List<CommitInfo> history(int max) throws IOException {
        List<CommitInfo> out = new ArrayList<>();
        ObjectId id = repo.resolve(Constants.R_HEADS + branch);
        if (id == null) return out;
        try (RevWalk w = new RevWalk(repo)) {
            w.markStart(w.parseCommit(id));
            for (RevCommit c : w) {
                if (out.size() >= max) break;
                out.add(new CommitInfo(c.name(), c.getCommitterIdent().getWhenAsInstant(), c.getShortMessage()));
            }
        }
        return out;
    }

    record CommitInfo(String id, java.time.Instant time, String message) {}

    // ---------------------------------------------------------------- 写入

    /**
     * 待提交的对象树。key 为仓库内相对路径（用 / 分隔），value 为已入库的 blob id。
     *
     * <p>以父提交内容为基线，因此"未变更"完全不需要重新记录，这也是增量备份不读盘的前提。
     */
    static final class Tree {
        private final TreeMap<String, ObjectId> entries = new TreeMap<>();

        void put(String path, ObjectId id) { entries.put(path, id); }
        void putAll(Map<String, ObjectId> other) { entries.putAll(other); }
        ObjectId get(String path) { return entries.get(path); }
        boolean contains(String path) { return entries.containsKey(path); }
        void remove(String path) { entries.remove(path); }
        boolean isEmpty() { return entries.isEmpty(); }
        int size() { return entries.size(); }
        Map<String, ObjectId> entries() { return entries; }

        /** 前缀查询（按字典序 subMap 实现，避免全表扫描）。 */
        java.util.Collection<String> pathsWithPrefix(String prefix) {
            if (prefix.isEmpty()) return java.util.List.copyOf(entries.keySet());
            return java.util.List.copyOf(entries.subMap(prefix, prefix + '\uffff').keySet());
        }
    }

    /**
     * 在父提交的基础上应用覆盖与删除，生成新提交。
     *
     * <p>语义是"增量"：只描述变化，未提及的路径沿用父提交内容。返回 null 表示树无变化。
     */
    RevCommit commit(Tree overrides, java.util.Set<String> removals, String message,
                     Map<String, String> extraRootFiles) throws Exception {
        RevCommit parent = head();
        // overrides 已经是"父提交 + 本次改动"的完整视图，这里只需复制并应用删除
        Map<String, ObjectId> merged = new TreeMap<>(overrides == null ? Map.of() : overrides.entries());
        if (removals != null) for (String r : removals) merged.remove(r);
        if (extraRootFiles != null) {
            for (Map.Entry<String, String> e : extraRootFiles.entrySet())
                merged.put(e.getKey(), insertBlob(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        {
            ObjectInserter ins = inserter();
            Node root = new Node();
            for (Map.Entry<String, ObjectId> e : merged.entrySet()) root.insert(e.getKey(), e.getValue());
            ObjectId treeId = insertNode(ins, root);
            if (parent != null && parent.getTree().getId().equals(treeId)) return null;

            CommitBuilder cb = new CommitBuilder();
            cb.setTreeId(treeId);
            PersonIdent ident = new PersonIdent("GitBackup", "gitbackup@localhost");
            cb.setAuthor(ident);
            cb.setCommitter(ident);
            cb.setMessage(message);
            if (parent != null) cb.setParentId(parent.getId());
            ObjectId commitId = ins.insert(cb);
            ins.flush();
            parentBlobsLoaded = false;
            parentBlobs.clear();
            RefUpdate ru = repo.updateRef(Constants.R_HEADS + branch);
            ru.setNewObjectId(commitId);
            ru.setRefLogMessage("gitbackup: " + message, false);
            if (parent != null) ru.setExpectedOldObjectId(parent.getId());
            else ru.setExpectedOldObjectId(ObjectId.zeroId());
            RefUpdate.Result result = ru.update();
            if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FORCED
                    && result != RefUpdate.Result.FAST_FORWARD && result != RefUpdate.Result.NO_CHANGE)
                throw new IOException("更新引用失败: " + result);
            try (RevWalk w = new RevWalk(repo)) { return w.parseCommit(commitId); }
        }
    }

    private static final class Node {
        final Map<String, ObjectId> files = new TreeMap<>();
        final Map<String, Node> dirs = new TreeMap<>();
        void insert(String path, ObjectId id) {
            int slash = path.indexOf('/');
            if (slash < 0) { files.put(path, id); return; }
            dirs.computeIfAbsent(path.substring(0, slash), k -> new Node())
                    .insert(path.substring(slash + 1), id);
        }
    }

    private static ObjectId insertNode(ObjectInserter ins, Node node) throws IOException {
        // Git 要求 tree 条目按"名字字节序"排列，且目录按 name + '/' 参与排序。
        // 先文件后目录会触发 treeNotSorted，远端 index-pack 会直接拒绝整个推送。
        record Entry(String sortKey, String name, FileMode mode, ObjectId id) {}
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, ObjectId> e : node.files.entrySet())
            entries.add(new Entry(e.getKey(), e.getKey(), FileMode.REGULAR_FILE, e.getValue()));
        for (Map.Entry<String, Node> e : node.dirs.entrySet())
            entries.add(new Entry(e.getKey() + "/", e.getKey(), FileMode.TREE, insertNode(ins, e.getValue())));
        entries.sort((a, b) -> compareGitNames(a.sortKey(), b.sortKey()));

        TreeFormatter tf = new TreeFormatter();
        for (Entry e : entries) tf.append(e.name(), e.mode(), e.id());
        return ins.insert(tf);
    }

    /** Git 的名字比较：按无符号字节比较，长度短者在前（等价于 memcmp + 长度）。 */
    private static int compareGitNames(String a, String b) {
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int diff = (x[i] & 0xFF) - (y[i] & 0xFF);
            if (diff != 0) return diff;
        }
        return x.length - y.length;
    }

    void push() throws Exception {
        if (!hasRemote()) throw new IOException("尚未配置远程仓库");
        var cmd = git.push().setRemote("origin")
                .setRefSpecs(new RefSpec(Constants.R_HEADS + branch + ":" + Constants.R_HEADS + branch))
                .setTimeout(120)
                .setTransportConfigCallback(t -> proxy.configure(t, ssh));
        if (credentials != null) cmd.setCredentialsProvider(credentials);
        boolean confirmed = false;
        for (var result : cmd.call()) {
            for (RemoteRefUpdate up : result.getRemoteUpdates()) {
                confirmed = true;
                if (up.getStatus() == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD)
                    throw new IOException("远端分支已被其它来源推进，本地历史与远端分叉。"
                            + "请先 fetch 让本地追上远端，或改用新的分支名，避免覆盖别人的备份。");
                if (up.getStatus() != RemoteRefUpdate.Status.OK && up.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE)
                    throw new IOException("远端拒绝推送: " + up.getStatus() + " " + up.getMessage());
            }
        }
        if (!confirmed) throw new IOException("远端未返回引用更新确认，无法确认推送成功");
    }

    /**
     * 与远端对齐。
     *
     * <p>两种场景必须区分：
     * <ul>
     *   <li>本地还没有任何提交、远端已有备份 → 采纳远端历史（换机、灾后重建的常规路径）</li>
     *   <li>本地已有提交 → 只更新远端引用，不自动合并，避免悄悄改写历史</li>
     * </ul>
     */
    RevCommit syncWithRemote() throws Exception {
        if (!hasRemote()) return head();
        RevCommit remoteHead = fetch();
        if (remoteHead == null) return head();
        String remoteFormat = formatVersionFromTree(remoteHead);
        if (remoteFormat != null && !FORMAT.trim().equals(remoteFormat))
            throw new IOException("远端备份格式为 " + remoteFormat + "，本插件只支持 " + FORMAT.trim());
        if (head() == null) {
            RefUpdate ru = repo.updateRef(Constants.R_HEADS + branch);
            ru.setNewObjectId(remoteHead.getId());
            ru.setForceUpdate(true);
            ru.setRefLogMessage("gitbackup: adopt remote history", false);
            RefUpdate.Result r = ru.update();
            if (r != RefUpdate.Result.NEW && r != RefUpdate.Result.FORCED && r != RefUpdate.Result.FAST_FORWARD)
                throw new IOException("无法采纳远端历史: " + r);
            parentBlobsLoaded = false;
            parentBlobs.clear();
            return remoteHead;
        }
        return head();
    }

    /** 拉取远端分支到 refs/remotes/origin/<branch>，返回远端提交 ID。 */
    RevCommit fetch() throws Exception {
        if (!hasRemote()) throw new IOException("尚未配置远程仓库");
        var cmd = git.fetch().setRemote("origin").setTimeout(120)
                .setTransportConfigCallback(t -> proxy.configure(t, ssh));
        if (credentials != null) cmd.setCredentialsProvider(credentials);
        cmd.call();
        ObjectId id = repo.resolve("refs/remotes/origin/" + branch);
        // 远端还没有该分支属于正常情况（首次创建备份仓库），返回 null 表示"远端无历史"。
        if (id == null) return null;
        try (RevWalk w = new RevWalk(repo)) { return w.parseCommit(id); }
    }

    /** 找出把远端提交合入本地的基准提交（用于跨机器恢复）。 */
    RevCommit commonAncestor(RevCommit remoteHead) throws IOException {
        RevCommit local = head();
        if (local == null) return remoteHead;
        try (RevWalk w = new RevWalk(repo)) {
            w.setRetainBody(false);
            w.markStart(w.parseCommit(local));
            w.markStart(w.parseCommit(remoteHead));
            return w.next();
        }
    }

    String formatVersionFromTree(RevCommit commit) throws IOException {
        if (commit == null) return null;
        byte[] data = readBlob(commit, VERSION_FILE);
        return data == null ? null : new String(data, java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    @Override public void close() {
        try { ssh.close(); } catch (Exception ignored) { }
        git.close();
    }
}
