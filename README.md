# GitBackup

面向 **Folia 26.1.2** 的世界存档增量备份与**区块级**恢复插件，备份数据存放在 Git 仓库（本地裸仓库 + 可选远端），
所有 Git 操作都在插件内部完成，**不依赖服务器上安装 git**。

当前版本：`0.2.0`（开发中，仅在本机 Lophine 26.1.2 上验证过）。

---

## 它做什么

- 按 **区块** 粒度备份：`region` / `entities` / `poi` 等区域目录中的每条区块记录被拆成独立 Git 对象。
- 只处理和上传**变更**：源文件大小与修改时间未变时直接复用已有对象，连读盘都省掉。
- 可按 **单个区块** 恢复到指定提交；恢复前自动建立快照，并登记可回滚记录。
- 定时备份使用 Quartz 六段 cron，多个计划各自独立、可指定不同世界。
- 世界与维度名称 **全部运行时自动发现**，不写死目录名。
- 默认排除玩家数据（`players/` 等）与假人玩家状态。

## 数据格式

```
<备份仓库>/
├── data/worlds/<命名空间>/<维度>/
│   ├── region/chunks/<区块X>.<区块Z>.bin        # 主世界方块数据记录（含 4 字节长度前缀的原始记录）
│   ├── entities/chunks/<区块X>.<区块Z>.bin      # 实体记录
│   ├── poi/chunks/<区块X>.<区块Z>.bin           # POI 记录
│   ├── <其他区域目录>/chunks/...                # 任何包含 r.<x>.<z>.mca 的目录都会被这样处理
│   └── files/<原相对路径>                       # level.dat、data/*.dat 等非区块存档文件
├── backup_info.json                             # 回滚/待生效恢复账本
└── version.txt                                  # 备份格式版本
```

区域目录名不写死：扫描时凡是出现 `r.<x>.<z>.mca` 的目录都按 Anvil 区域目录处理，
因此自定义维度、插件额外生成的区域目录都会被正确拆分并纳入备份。

## 命令

所有命令统一受 `gitbackup.admin` 一个权限节点控制。

```
/gitbackup status
/gitbackup backup [定时计划名]
/gitbackup list [<世界> [<区块x,z>]]
/gitbackup restore chunk <世界> <x,z> [<x,z> ...] [提交]
/gitbackup restore coordinate <世界> <x,y,z> <x,y,z> [提交]
/gitbackup rollback [记录ID]
```

- `list` 不带参数时使用玩家当前所在世界与区块。
- `restore` 省略提交哈希时会先列出该区块可用的备份时间点（可点击恢复）。
- `coordinate` 的 y 坐标只参与换算，不参与匹配。
- `rollback` 不带参数时列出最近恢复记录，可一键回滚或传送到受影响区域中心。

## 恢复的安全模型

恢复前会判定**能否在线热恢复**：

| 情况 | 行为 |
| --- | --- |
| 目标记录存在，且能放进当前分配扇区 | **在线热恢复**：区块卸载 → 就地改写记录 → 强制丢弃可能被重新加载的内存副本 |
| 目标记录需要更多扇区 | 排队，在**下次启动加载世界之前**重建 region 文件 |
| 目标版本没有该区块、但磁盘上存在 | 排队，在下次启动时删除（不在线静默删除） |
| 目标版本没有、磁盘上也没有 | 无需处理 |

热恢复只改写记录数据、**绝不改动 region 目录表**，因为服务端把目录表缓存在内存里；
改动目录表会让服务端读到错误扇区。所以"需要改目录表"的恢复一律推迟到世界未加载时执行。

## 配置

配置文件为 `plugins/GitBackup/config.yml`，主要项：

```yaml
repository:
  local-path: backup-repository      # 备份仓库位置（相对插件目录）
  remote-url: ""                     # 支持 https:// 与 ssh://；留空则只做本地提交
  branch: main
  https-username: ""                 # 或用环境变量 GITBACKUP_HTTPS_USERNAME
  https-token: ""                    # 或用环境变量 GITBACKUP_HTTPS_TOKEN
  ssh-key-file: ssh/id_ed25519
  proxy: ""                          # http://host:port 或 socks5://host:port；或用 GITBACKUP_PROXY

worlds:
  auto-discover: true
  list:
    "minecraft:overworld": { enabled: true }

backup:
  validate: true                     # 校验每条区块记录可解压且 xPos/zPos 自洽
  history-limit: 50
  exclude: [players, playerdata, advancements, stats, fakeplayerdata, resume_fakeplayerdata, dimensions]

schedules:
  hourly: { cron: "0 0 * * * ?", worlds: [] }

restore:
  pre-restore-snapshot: true
  teleport-players: true
```

- 首次启动会把发现到的世界写进 `worlds.list`，**只有原版三个维度默认开启**，其余默认关闭。
- `dimensions/` 默认排除，因为每个维度在 Bukkit 中就是独立世界、会被单独作为备份目标。
  若要"整个世界目录一次备份"，把 `dimensions` 从 `exclude` 删掉。

## 离线工具

同一份 jar 也可以在没有服务端的情况下操作仓库（停服恢复、巡检、灾后重建）：

```
java -jar GitBackup.jar backup  <仓库> --world <key>=<目录>[;<key>=<目录>] [--push] [--proxy URL]
java -jar GitBackup.jar list    <仓库> [--branch main]
java -jar GitBackup.jar show    <仓库> --commit <hash> [--world <key>]
java -jar GitBackup.jar plan    <仓库> --world <key>=<目录> --commit <hash> --chunks x,z[;x,z]
java -jar GitBackup.jar restore <仓库> --world <key>=<目录> --commit <hash> --chunks x,z[;x,z]
java -jar GitBackup.jar pending <仓库> --dir <待生效目录>
java -jar GitBackup.jar cron    "<六段表达式>"
```

人工 `git clone` 出来的普通仓库也能直接用于恢复（支持 `.git` 布局）。

## 网络与代理

JGit 7.x 不再读取 Git 的代理配置，插件通过替换 HTTP 连接工厂实现代理，只影响本插件自身的传输，
不修改 JVM 全局属性。经代理推送大包时，建议先确认代理支持较长连接。

## 已知限制

- 只处理存档，不含玩家数据；玩家数据目录默认排除。
- 在线恢复要求目标世界已加载；未加载的世界请用离线工具或先加载。
- 冷态恢复（重建 region 文件）只在世界加载前生效，需重启一次。
- 备份仓库体积约等于存档体积（每区块记录按原始压缩字节存储，Git 只在文件级去重）。
  超大存档请评估远端配额，必要时只用本地仓库 + 定期归档。
- 仅在 Lophine 26.1.2 实测；其他服务端/版本需重新验证 Anvil 行为。

## 构建

```
./gradlew shadowJar      # 产物 build/libs/GitBackup-<version>.jar
./gradlew test           # 单元测试（Anvil 读写、NBT 校验、增量、恢复计划）
```

需要 Java 25（与 26.1.2 的 API 版本匹配）。
