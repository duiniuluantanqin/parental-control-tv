# 部署到阿里云 ECS

复用 ECS 已有的 Caddy，只增加一个域名站点；Python 后台交给 systemd。无需 Docker，也不需要重新安装 Caddy。

```text
浏览器 / 电视 → https://tv.example.com → 现有 Caddy → 127.0.0.1:8080
GitHub Actions → SSH / rsync → /var/www/html/tvparental → 重启 tvparental
```

下面以 `tv.example.com`、部署用户 `tvparental-deploy` 为例。假设 ECS 使用 systemd，已安装 Python ≥ 3.11、rsync、curl、openssl；用 `python3 --version` 确认版本。若使用 `/usr/bin/python3.11`，相应修改下文的 `ExecStart`。

## 1. 一次性准备部署账户

以下 Linux 命令在 ECS 上通过已有的管理员账户执行。账户已存在时跳过 `useradd`。

```bash
sudo useradd -m -U -s /bin/bash tvparental-deploy
sudo install -d -m 700 -o tvparental-deploy -g tvparental-deploy /home/tvparental-deploy/.ssh
sudo install -d -m 700 -o tvparental-deploy -g tvparental-deploy /var/www/html/tvparental
sudo -u tvparental-deploy mkdir -p /var/www/html/tvparental/backend/data
```

在本机 PowerShell 生成专用部署密钥，提示输入 passphrase 时回车两次。**已有 `tv-parental-deploy` 密钥对就直接复用，不要覆盖。** 新密钥放在用户目录，避免放进仓库：

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.ssh" | Out-Null
ssh-keygen -t ed25519 -f "$env:USERPROFILE\.ssh\tv-parental-deploy"
Get-Content "$env:USERPROFILE\.ssh\tv-parental-deploy.pub"
```

将公钥整行填入下面的位置，在 ECS 上执行（追加，不覆盖已有公钥）：

```bash
sudo -u tvparental-deploy tee -a /home/tvparental-deploy/.ssh/authorized_keys <<'EOF'
替换成 ssh-ed25519 开头的整行部署公钥
EOF
sudo chmod 600 /home/tvparental-deploy/.ssh/authorized_keys
sudo visudo -f /etc/sudoers.d/tvparental
```

在 visudo 中只加入这一条授权：

```text
tvparental-deploy ALL=(root) NOPASSWD: /usr/bin/systemctl restart tvparental
```

本机用 `ssh -i <私钥路径> tvparental-deploy@<ECS公网IP> whoami` 验证密钥登录，非默认 SSH 端口加 `-p <端口>`。如果 sshd 设置了 `AllowUsers`，需允许该账户。部署账户需要密钥登录，不要修改已有管理员的 SSH 登录配置。

## 2. 一次性准备后台服务

新部署在 ECS 上执行下面命令，生成随机管理员令牌写入 `.env`，文件已存在时会拒绝覆盖：

```bash
sudo -u tvparental-deploy sh -c '
  set -euC
  umask 077
  token=$(openssl rand -hex 32)
  printf "TV_PARENTAL_ADMIN_TOKEN=%s\n" "$token" > /var/www/html/tvparental/backend/.env
'
sudo tee /etc/systemd/system/tvparental.service > /dev/null <<'EOF'
[Unit]
Description=TV Parental admin backend
After=network.target

[Service]
User=tvparental-deploy
WorkingDirectory=/var/www/html/tvparental
Environment=HOST=127.0.0.1
Environment=PORT=8080
Environment=TV_PARENTAL_DATA_FILE=/var/www/html/tvparental/backend/data/state.json
EnvironmentFile=/var/www/html/tvparental/backend/.env
ExecStart=/usr/bin/python3 -u backend/server.py
Restart=on-failure
RestartSec=3
UMask=0077

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable tvparental
```

此时还没有上传代码，**只启用开机自启，首次启动由 Actions 完成**。`.env` 由 systemd 加载，Python 本身不会读取它；域名配置在 Caddy 和 GitHub 中即可。先用 `sudo ss -ltnp 'sport = :8080'` 确认端口未被其他服务占用；若需换端口，同时修改上面的 `PORT`、Caddy 的代理地址及工作流的本机检查地址。

已有部署应先备份并保留 `.env` 和题库。旧版默认数据文件是 `backend/data.json`，如仍在使用它，在 `.env` 中设置 `TV_PARENTAL_DATA_FILE=/var/www/html/tvparental/backend/data.json`；本流程不会自动迁移数据。部署目录限制为仅部署用户可访问，Caddy 无需读取该目录。

## 3. 为现有 Caddy 增加站点

将新域名的 A 记录指向 ECS，安全组和主机防火墙允许 TCP 80/443。**保留其他域名配置**，在现有 Caddyfile 追加：

```caddy
tv.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

主配置通常是 `/etc/caddy/Caddyfile`，可用 `systemctl cat caddy` 核对；若已有 `import` 结构，也可将新站点放进已被导入的配置文件。校验完整主配置成功后再重载：

```bash
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile && sudo systemctl reload caddy
```

Caddy 自动管理该域名的 HTTPS。后台首次部署前出现 502 属正常。不要启动仓库中的 Compose：其中的另一个 Caddy 会与现有服务争用 80/443。

## 4. 配置 GitHub 并首次发布

进入仓库 **Settings → Secrets and variables → Actions**：

| 类型 | 名称 | 内容 |
|---|---|---|
| Secret | `ECS_HOST` | ECS 公网 IP 或 SSH 域名，不带协议和端口 |
| Secret | `ECS_SSH_KEY` | 部署私钥全文，包含 BEGIN/END 行 |
| Secret | `ECS_HOST_KEY` | 在可信的 ECS 会话执行 `cat /etc/ssh/ssh_host_ed25519_key.pub` 得到的整行主机公钥 |
| Secret，可选 | `ECS_SSH_PORT` | SSH 端口，默认 `22` |
| Variable | `TV_PARENTAL_DOMAIN` | `tv.example.com`，不带 `https://` 或路径 |

`ECS_HOST_KEY` 用于确认连接的是自己的服务器，和部署账户的 `.pub` 是两把不同的公钥。工作流固定使用 `tvparental-deploy`，无需设置 `ECS_USER`。管理员令牌只留在 ECS，不需要上传到 GitHub。

**SSH 端口必须能被 GitHub runner 访问。** 仅放行家庭 IP 会导致 Actions 超时；GitHub 托管 runner 没有固定的单一出口 IP，改 SSH 域名也不会解决。若必须使用固定来源 IP，需要自托管 runner 或专门的网络通道。

提交并推送工作流及代码后，在 **Actions → deploy-ecs → Run workflow** 选择 `main`。之后 `main` 上的 `backend/**`、种子题库或工作流变更会自动触发发布：

1. 运行后端 API 测试；通过后才部署，其他分支不会部署。
2. rsync 同步后台和种子题库，保留 `.env`、`data/` 和旧的 `data.json`。
3. 重启 `tvparental`，等待本机接口就绪，再检查公网 HTTPS 接口。部署串行执行，避免交叉覆盖。

成功后浏览器打开 `https://tv.example.com`，电视后台地址也填这个地址。在可信 ECS 会话执行 `sudo cat /var/www/html/tvparental/backend/.env` 查看管理员令牌，用它连接管理页。

## 日常维护

- 题库与规则通常保存在 `backend/data/state.json`；备份该目录和 `.env`。种子题库只用于数据文件不存在时初始化，更新种子不会替换已经保存的题库。
- 发布失败：先看 Actions 失败步骤，再在 ECS 执行 `sudo journalctl -u tvparental -n 100 --no-pager`。本机接口正常、HTTPS 失败时检查 DNS、Caddy 和安全组。
- 自动发布会短暂重启后台，没有自动回滚。回退时在 GitHub 撤销有问题的提交并推送到 `main`。
- 部署密钥可读写本应用代码、令牌和数据，应妥善保管；管理员令牌不要提交到 Git，也不要直接写进命令历史。
