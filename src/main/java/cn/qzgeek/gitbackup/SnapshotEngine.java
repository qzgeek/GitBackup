package cn.qzgeek.gitbackup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

final class SnapshotEngine {
    private static final Set<String> ANVIL_DIRS = Set.of("region", "entities", "poi");
    private static final Set<String> PLAYER_DIRS = Set.of("playerdata", "advancements", "stats");
    private final GitBackupPlugin plugin;
    private final GitRepository repository;

    SnapshotEngine(GitBackupPlugin plugin, GitRepository repository) { this.plugin = plugin; this.repository = repository; }

    SnapshotResult backup(List<GitBackupConfig.WorldTarget> worlds) throws Exception {
        Set<String> expected = new HashSet<>();
        for (GitBackupConfig.WorldTarget world : worlds) {
            Path destination = repository.root().resolve(world.repositoryPath());
            expected.addAll(copyWorld(world, destination));
        }
        pruneStale(expected);
        return new SnapshotResult(expected.size());
    }

    private Set<String> copyWorld(GitBackupConfig.WorldTarget world, Path destination) throws IOException {
        Set<String> expected = new HashSet<>();
        Path root = world.root();
        if (!Files.isDirectory(root)) return expected;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path source : walk.filter(Files::isRegularFile).toList()) {
                Path relative = root.relativize(source);
                if (relative.getNameCount() > 0 && PLAYER_DIRS.contains(relative.getName(0).toString())) continue;
                if (relative.getFileName().toString().equals("session.lock")) continue;
                String first = relative.getNameCount() == 0 ? "" : relative.getName(0).toString();
                if (ANVIL_DIRS.contains(first) && source.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")) {
                    String kind = first;
                    String regionName = source.getFileName().toString().substring(0, source.getFileName().toString().length() - 4);
                    for (AnvilRegion.Record record : AnvilRegion.read(source)) {
                        int regionX = Integer.parseInt(regionName.substring(2, regionName.indexOf('.', 2)));
                        int regionZ = Integer.parseInt(regionName.substring(regionName.indexOf('.', 2) + 1));
                        int chunkX = regionX * 32 + record.localX();
                        int chunkZ = regionZ * 32 + record.localZ();
                        Path target = destination.resolve(kind).resolve("chunks").resolve(chunkX + "." + chunkZ + ".bin");
                        Files.createDirectories(target.getParent());
                        Files.write(target, record.bytes());
                        expected.add(repository.root().relativize(target).toString());
                    }
                } else {
                    Path target = destination.resolve("files").resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    expected.add(repository.root().relativize(target).toString());
                }
            }
        }
        return expected;
    }

    private void pruneStale(Set<String> expected) throws IOException {
        Path data = repository.root().resolve("data");
        if (!Files.isDirectory(data)) return;
        try (Stream<Path> walk = Files.walk(data)) {
            walk.sorted(Comparator.reverseOrder()).filter(Files::isRegularFile).forEach(path -> {
                String relative = repository.root().relativize(path).toString();
                if (!expected.contains(relative) && !relative.endsWith(".gitkeep")) {
                    try { Files.deleteIfExists(path); } catch (IOException e) { throw new SnapshotIOException(e); }
                }
            });
        } catch (SnapshotIOException e) { throw e.io; }
    }

    void restoreChunks(String revision, GitBackupConfig.WorldTarget world, String kind,
                       Set<ChunkKey> chunks) throws Exception {
        Path destination = world.root().resolve(kind);
        Set<String> regions = new HashSet<>();
        for (ChunkKey chunk : chunks) {
            int regionX = Math.floorDiv(chunk.x, 32), regionZ = Math.floorDiv(chunk.z, 32);
            String region = "r." + regionX + "." + regionZ + ".mca";
            regions.add(region);
            List<AnvilRegion.Record> records = new ArrayList<>();
            for (int lx = 0; lx < 32; lx++) for (int lz = 0; lz < 32; lz++) {
                int x = regionX * 32 + lx, z = regionZ * 32 + lz;
                byte[] bytes = repository.readBlob(revision, world.repositoryPath() + "/" + kind + "/chunks/" + x + "." + z + ".bin");
                if (bytes != null) records.add(new AnvilRegion.Record(lx, lz, bytes));
            }
            AnvilRegion.writeRegion(destination.resolve(region).toAbsolutePath(), records);
        }
    }

    record ChunkKey(int x, int z) {}
    record SnapshotResult(int objects) {}
    private static final class SnapshotIOException extends RuntimeException {
        final IOException io; SnapshotIOException(IOException io) { super(io); this.io = io; }
    }
}
