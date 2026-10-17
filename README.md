# GitBackup

GitBackup 是面向 Folia 26.1.2 的增量世界存档备份插件。

当前阶段只处理世界存档，不处理玩家数据。备份对象包括 Anvil 区块记录、实体区块记录、POI 区块记录以及配置中纳入的其他存档文件。

## 重要说明

- 首次使用前必须配置一个专用备份仓库。
- Git 仓库凭据只应存放在服务器插件目录或环境变量中。
- 恢复前会自动建立恢复前快照，恢复记录写入 backup_info.json。
- 恢复操作默认需要管理员确认，并且会短暂冻结目标区域。
- 当前版本专门针对 Folia 26.1.2；其他版本需要重新验证 Anvil 数据和 API 行为。

## 命令

```text
/gitbackup status
/gitbackup backup [配置名]
/gitbackup list [世界 区块]
/gitbackup restore chunk <世界> <区块1> <区块2> [时间或提交]
/gitbackup restore coordinate <世界> <x1,y1,z1> <x2,y2,z2> [时间或提交]
/gitbackup rollback [恢复记录ID]
```

所有命令统一使用 `gitbackup.admin` 权限。
