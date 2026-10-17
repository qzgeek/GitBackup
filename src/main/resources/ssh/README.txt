GitBackup SSH 配置说明

将用户自己的 SSH 私钥放入插件目录下的 ssh/ 文件夹，例如：

  plugins/GitBackup/ssh/id_ed25519

然后在 config.yml 中设置：

  repository:
    protocol: ssh
    ssh-key-file: ssh/id_ed25519

私钥文件不会被备份到 Git 仓库。请将文件权限设置为仅服务器进程可读。
不要把真实私钥提交到源码仓库或备份仓库。

HTTPS 模式可以在 config.yml 中填写用户名和 Personal Access Token，也可以使用
系统环境变量：GITBACKUP_HTTPS_USERNAME 和 GITBACKUP_HTTPS_TOKEN。
