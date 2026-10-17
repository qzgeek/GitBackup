package cn.qzgeek.gitbackup;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class GitBackupConfig {
    final Path repositoryPath;
    final String remoteUrl;
    final String branch;
    final String protocol;
    final String httpsUsername;
    final String httpsToken;
    final String sshKeyFile;
    final boolean autoDiscoverWorlds;
    final int workerThreads;
    final List<Schedule> schedules;

    private GitBackupConfig(Path repositoryPath, String remoteUrl, String branch, String protocol,
                            String httpsUsername, String httpsToken, String sshKeyFile,
                            boolean autoDiscoverWorlds, int workerThreads, List<Schedule> schedules) {
        this.repositoryPath = repositoryPath;
        this.remoteUrl = remoteUrl;
        this.branch = branch;
        this.protocol = protocol;
        this.httpsUsername = httpsUsername;
        this.httpsToken = httpsToken;
        this.sshKeyFile = sshKeyFile;
        this.autoDiscoverWorlds = autoDiscoverWorlds;
        this.workerThreads = workerThreads;
        this.schedules = schedules;
    }

    static GitBackupConfig load(FileConfiguration c, Path dataFolder) {
        String repository = c.getString("repository.local-path", "backup-repository");
        Path repoPath = Path.of(repository);
        if (!repoPath.isAbsolute()) repoPath = dataFolder.resolve(repoPath);
        List<Schedule> schedules = new ArrayList<>();
        ConfigurationSection section = c.getConfigurationSection("schedules");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String cron = section.getString(key + ".cron", "0 0 * * * ?");
                schedules.add(new Schedule(key, cron));
            }
        }
        return new GitBackupConfig(repoPath,
                c.getString("repository.remote-url", ""),
                c.getString("repository.branch", "main"),
                c.getString("repository.protocol", "https").toLowerCase(),
                c.getString("repository.https-username", ""),
                c.getString("repository.https-token", ""),
                c.getString("repository.ssh-key-file", "ssh/id_ed25519"),
                c.getBoolean("worlds.auto-discover", true),
                Math.max(1, c.getInt("performance.worker-threads", 2)), schedules);
    }

    List<WorldTarget> discoverWorlds() {
        List<WorldTarget> result = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            Path root = world.getWorldFolder().toPath();
            String namespace = world.getKey().namespace();
            String dimension = world.getKey().value();
            result.add(new WorldTarget(world.getName(), namespace, dimension, root));
        }
        return result;
    }

    record Schedule(String id, String cron) {}
    record WorldTarget(String name, String namespace, String dimension, Path root) {
        String repositoryPath() { return "data/worlds/" + namespace + "/" + dimension; }
    }
}
