package cn.qzgeek.gitbackup;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

final class GitRepository implements AutoCloseable {
    private final GitBackupPlugin plugin;
    private final GitBackupConfig config;
    private final Git git;
    private final ReentrantLock lock = new ReentrantLock();

    private GitRepository(GitBackupPlugin plugin, GitBackupConfig config, Git git) {
        this.plugin = plugin; this.config = config; this.git = git;
    }

    static GitRepository open(GitBackupPlugin plugin, GitBackupConfig config) throws Exception {
        Files.createDirectories(config.repositoryPath);
        Git git;
        if (Files.exists(config.repositoryPath.resolve(".git"))) {
            git = Git.open(config.repositoryPath.toFile());
        } else {
            git = Git.init().setDirectory(config.repositoryPath.toFile()).call();
            Files.writeString(config.repositoryPath.resolve("version.txt"), "GitBackup format 1\n");
            Files.writeString(config.repositoryPath.resolve("README.md"), "GitBackup world backup repository.\n");
            git.add().addFilepattern("version.txt").addFilepattern("README.md").call();
            git.commit().setMessage("Initialize GitBackup repository").call();
        }
        GitRepository result = new GitRepository(plugin, config, git);
        result.configureRemote();
        return result;
    }

    private void configureRemote() throws Exception {
        if (config.remoteUrl == null || config.remoteUrl.isBlank()) return;
        var remotes = git.remoteList().call();
        boolean exists = remotes.stream().anyMatch(r -> r.getName().equals("origin"));
        if (!exists) git.remoteAdd().setName("origin").setUri(new org.eclipse.jgit.transport.URIish(config.remoteUrl)).call();
        else git.remoteSetUrl().setRemoteName("origin").setRemoteUri(new org.eclipse.jgit.transport.URIish(config.remoteUrl)).call();
    }

    void pull() throws Exception {
        if (config.remoteUrl == null || config.remoteUrl.isBlank()) return;
        lock.lock();
        try {
            var cmd = git.pull().setRemote("origin").setRemoteBranchName(config.branch).setRebase(false);
            CredentialsProvider provider = credentials();
            if (provider != null) cmd.setCredentialsProvider(provider);
            cmd.call();
        } finally { lock.unlock(); }
    }

    String commitAndPush(String message) throws Exception {
        lock.lock();
        try {
            git.add().addFilepattern(".").call();
            var status = git.status().call();
            if (status.isClean()) return git.getRepository().getBranch();
            RevCommit commit = git.commit().setAll(true).setMessage(message).call();
            if (config.remoteUrl != null && !config.remoteUrl.isBlank()) {
                var push = git.push().setRemote("origin").setPushAll();
                CredentialsProvider provider = credentials();
                if (provider != null) push.setCredentialsProvider(provider);
                push.call();
            }
            return commit.getName();
        } finally { lock.unlock(); }
    }

    String head() throws IOException { return git.getRepository().resolve(Constants.HEAD).name(); }

    byte[] readBlob(String revision, String path) throws Exception {
        lock.lock();
        try (RevWalk walk = new RevWalk(git.getRepository())) {
            ObjectId commitId = git.getRepository().resolve(revision);
            if (commitId == null) throw new IOException("找不到 Git 提交: " + revision);
            RevCommit commit = walk.parseCommit(commitId);
            try (TreeWalk tree = TreeWalk.forPath(git.getRepository(), path, commit.getTree())) {
                if (tree == null) return null;
                ObjectLoader loader = git.getRepository().open(tree.getObjectId(0));
                return loader.getBytes();
            }
        } finally { lock.unlock(); }
    }

    private CredentialsProvider credentials() {
        if (!"https".equals(config.protocol)) return null;
        String user = config.httpsUsername == null || config.httpsUsername.isBlank()
                ? System.getenv().getOrDefault("GITBACKUP_HTTPS_USERNAME", "x-access-token") : config.httpsUsername;
        String token = config.httpsToken == null || config.httpsToken.isBlank()
                ? System.getenv().getOrDefault("GITBACKUP_HTTPS_TOKEN", "") : config.httpsToken;
        return token.isBlank() ? null : new UsernamePasswordCredentialsProvider(user, token);
    }

    Path root() { return config.repositoryPath; }
    Git git() { return git; }

    @Override public void close() { git.close(); }
}
