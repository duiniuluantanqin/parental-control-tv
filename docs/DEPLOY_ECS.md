# 部署到阿里云 ECS

复用 ECS 已有的 Caddy，只增加一个域名站点；Python 后台交给 systemd。无需 Docker，也不需要重新安装 Caddy。

```text
浏览器 / 电视 → https://tvparental.mediainsight360.com → 现有 Caddy → 127.0.0.1:8080
GitHub Actions → SSH / rsync → /var/www/html/tvparental → 重启 tvparental
```

## 当前部署信息

| 项目 | 当前值 |
|---|---|
| 管理页面 / 电视后台地址 | https://tvparental.mediainsight360.com/ |
| ECS 公网 IP / SSH 端口 | `47.109.94.124` / `22` |
| 部署账户 | `tvparental-deploy` |
| 本机部署私钥 | `C:\Users\49581\.ssh\tv-parental-deploy` |
| 应用目录 / 服务名 | `/var/www/html/tvparental` / `tvparental` |
| 后台监听地址 | `127.0.0.1:8080` |

**当前环境已发布，日常登录、重设管理员密码和排障直接看第 5 节。** 第 1–4 节用于首次部署或重建服务器；迁移时替换本文的 IP、域名和密钥路径。

ECS 使用 systemd，需要 Python ≥ 3.11、rsync、curl。当前已验证 `/usr/bin/python3` 为 Python 3.12.3；重建时用 `python3 --version` 确认版本。若使用 `/usr/bin/python3.11`，相应修改下文的 `ExecStart`。

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

本机验证密钥登录，成功时输出 `tvparental-deploy`：

```powershell
ssh -o BatchMode=yes -o IdentitiesOnly=yes -i "C:\Users\49581\.ssh\tv-parental-deploy" tvparental-deploy@47.109.94.124 whoami
```

`-i` 后面必须是私钥文件，填 `.ssh` 目录会报 `Load key ... Operation not supported on socket`。`BatchMode=yes` 会在认证失败时直接报错，避免退回密码提示；此部署使用密钥登录，无需输入账户密码。非默认 SSH 端口加 `-p <端口>`。如果 sshd 设置了 `AllowUsers`，需允许该账户。

公钥要安装到 **`/home/tvparental-deploy/.ssh/authorized_keys`**，放在 `/root/.ssh/authorized_keys` 只对 root 登录生效。该文件应包含本机 `C:\Users\49581\.ssh\tv-parental-deploy.pub` 的完整内容；有多份同名密钥时，以 `-i` 指定私钥对应的 `.pub` 为准。

## 2. 一次性准备后台服务

新部署先创建空的环境文件（已有文件不覆盖），注册服务。管理员账号会在 SQLite 初始化时创建为 `admin/admin`：

```bash
sudo -u tvparental-deploy touch /var/www/html/tvparental/backend/.env
sudo chmod 600 /var/www/html/tvparental/backend/.env
sudo tee /etc/systemd/system/tvparental.service > /dev/null <<'EOF'
[Unit]
Description=TV Parental admin backend
After=network.target

[Service]
User=tvparental-deploy
WorkingDirectory=/var/www/html/tvparental
Environment=HOST=127.0.0.1
Environment=PORT=8080
Environment=TV_PARENTAL_DATA_FILE=/var/www/html/tvparental/backend/data/state.sqlite3
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

`systemctl enable tvparental` 只设置开机自启，不会立即启动服务。此时还没有上传代码，**首次启动由 Actions 上传代码并重启服务来完成**。`.env` 由 systemd 加载，Python 本身不会读取它；域名配置在 Caddy 和 GitHub 中即可。先用 `sudo ss -ltnp 'sport = :8080'` 确认端口未被其他服务占用；若需换端口，同时修改上面的 `PORT`、Caddy 的代理地址及工作流的本机检查地址。

已有部署应先备份并保留 `.env` 和题库。后台将状态保存到 SQLite；数据库不存在时会初始化默认状态，不会导入旧 JSON 文件。迁移现有数据应先将 JSON 状态导入 SQLite，再将 `.env` 中的 `TV_PARENTAL_DATA_FILE` 指向 `/var/www/html/tvparental/backend/data/state.sqlite3`。部署目录限制为仅部署用户可访问，Caddy 无需读取该目录。

## 3. 为现有 Caddy 增加站点

将新域名的 A 记录指向 ECS，安全组和主机防火墙允许 TCP 80/443。**保留其他域名配置**，在现有 Caddyfile 追加：

```caddy
tvparental.mediainsight360.com {
    reverse_proxy 127.0.0.1:8080
}
```

当前 Caddy 配置关闭了 HTTP 自动跳转。2026-11-03 排查时，HTTPS 页面、CSS、JS 和题库接口均正常，HTTP 返回空的 200 响应，浏览器因此显示空白。要让 HTTP 地址也可用，在保留现有站点的基础上追加该域名的跳转：

```caddy
http://tvparental.mediainsight360.com {
    redir https://tvparental.mediainsight360.com{uri} 308
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
| Secret | `ECS_HOST` | `47.109.94.124`，不带协议和端口 |
| Secret | `ECS_SSH_KEY` | 本机 `C:\Users\49581\.ssh\tv-parental-deploy` 私钥全文，包含 BEGIN/END 行 |
| Secret | `ECS_HOST_KEY` | 在可信的 ECS 会话执行 `cat /etc/ssh/ssh_host_ed25519_key.pub` 得到的整行主机公钥 |
| Secret，可选 | `ECS_SSH_PORT` | SSH 端口，默认 `22` |
| Variable | `TV_PARENTAL_DOMAIN` | `tvparental.mediainsight360.com`，不带 `https://` 或路径 |

`ECS_HOST_KEY` 用于确认连接的是自己的服务器，和部署账户的 `.pub` 是两把不同的公钥。工作流固定使用 `tvparental-deploy`，无需设置 `ECS_USER`。管理员密码通过 ECS 命令设置，不需要上传到 GitHub。

**SSH 端口必须能被 GitHub runner 访问。** 仅放行家庭 IP 会导致 Actions 超时；GitHub 托管 runner 没有固定的单一出口 IP，改 SSH 域名也不会解决。若必须使用固定来源 IP，需要自托管 runner 或专门的网络通道。

提交并推送工作流及代码后，在 **Actions → deploy-ecs → Run workflow** 选择 `main`。之后 `main` 上的 `backend/**`、根目录 `VERSION`、种子题库或工作流变更会自动触发发布：

1. 运行后端 API 测试；通过后才部署，其他分支不会部署。
2. rsync 同步后台和种子题库，保留 `.env`、`data/` 和 SQLite 数据库。
3. 重启 `tvparental`，等待本机接口就绪，再检查公网 HTTPS 接口。部署串行执行，避免交叉覆盖。

首次发布完成并等待服务启动后，打开管理页，用 `admin/admin` 登录，然后通过右上角“修改密码”设置新密码（至少 10 个字符）。账号散列保存在 `backend/data/state.sqlite3`。已存在的 SQLite 题库、规则、设备和记录会保留；旧 `admin.json` 不再用于登录或自动导入。

**从旧版令牌登录升级：** 无需运行额外的账号初始化命令；原 `.env` 中的 `TV_PARENTAL_ADMIN_TOKEN` 已不再使用。首次启动会在现有 SQLite 数据库中创建默认管理员账号，无需重新初始化题库或重复配置 Caddy。

## 5. 日常使用与排障

### SSH 登录

在本机 PowerShell 执行：

```powershell
ssh -o IdentitiesOnly=yes -i "C:\Users\49581\.ssh\tv-parental-deploy" tvparental-deploy@47.109.94.124
```

该账户只授权免密重启本应用。编辑 systemd、Caddy 配置或执行其他需要管理员权限的命令，应使用已有的 root / 管理员会话。

### 登录管理页与重设密码

1. 打开 https://tvparental.mediainsight360.com/ 。
2. 使用 `admin/admin` 登录，首次登录后通过右上角“修改密码”更换默认密码。
3. “使用规则”页面（`/settings`）管理每日可用时长、答题间隔、答错锁定和同步间隔；“题库管理”页面（`/questions`）筛选、添加或删除题目。添加题目会打开对话框，类别从下拉框选择；点击题目可查看详情，包括类别、难度、四个选项和正确答案。本机预览步骤见 [README 的“本机后台”](../README.md#本机后台)。
4. 使用完成后可点击右上角“退出登录”。会话最长保持 12 小时；服务重启后需重新登录。

忘记用户名或密码时，以部署账户 SSH 登录 ECS 并执行：

```bash
cd /var/www/html/tvparental
/usr/bin/python3 backend/server.py --set-admin
```

这会重设管理员用户名和密码、撤销当前所有登录会话，题库与规则保留。服务只保存加盐的密码散列，无法查询密码明文。连续输错 5 次会暂停登录一分钟，稍后重试。`--set-admin` 应以 `tvparental-deploy` 身份运行，确保 SQLite 文件仍可被服务读取。

### DeepSeek AI 出题

AI 出题在后台的“题库管理 → AI 出题”对话框中使用。选择古诗词时输入作品名（可附作者）；数学、地理历史和科学类别会直接随机生成，无需输入题目名称。默认一次生成 5 道，可选 1–10 道；每道题有四个选项和一个正确答案。AI 与手动出题都可选择“低、中、高”难度，默认“中”。所选类别和难度会随题目预览、入库和同步保留。已有题库的“小学、初中、高中”在读取时分别对应“低、中、高”，无需手动迁移或修改电视应用。

首次配置 Key：

1. 用上面的 SSH 命令登录部署账户，用编辑器打开 `/var/www/html/tvparental/backend/.env`，保留已有配置，添加或修改这一行：

   ```dotenv
   DEEPSEEK_API_KEY=填入你自己的DeepSeekKey
   ```

   真实 Key 仅保存在 ECS 的这个文件中，不填写在管理页面、Android 或 GitHub 仓库中。`.env` 已由 Git 和自动部署排除。
2. 保存后以部署账户重启服务，使 systemd 重新加载环境文件：

   ```bash
   sudo -n /usr/bin/systemctl restart tvparental
   ```

   更新 Key 时同样需要重启；重启会清除浏览器登录会话，重新登录即可。尚未配置 Key 时，手动添加题目和电视同步照常可用。
3. 打开 https://tvparental.mediainsight360.com/questions ，登录后点击“AI 出题”。选择古诗词并输入例如 `静夜思（李白）`，或选择数学、地理历史、科学直接随机出题；再选择数量和难度，点击“开始出题”。
4. 页面会显示诗词名称和作者，或所选的随机类别，以及题目、选项和正确答案。核对内容，取消勾选不需要的题目，点击“加入题库”。生成预览时不入库，只有选中的题目会保存；相同题干自动跳过。之后电视按同步间隔获取新题目。

每次点击“开始出题 / 重新出题”都会调用一次 DeepSeek；勾选与加入题库不会再次调用。关闭弹窗后当前生成请求仍会继续，再打开可查看结果。模型可能出现知识错误，预览时需核对事实和答案。重名或生僻作品可在名称后补充作者；生成不完整或格式不正确时不会保存任何题目，可减少数量后重试。

接口固定使用 `https://api.deepseek.com/chat/completions`、模型 `deepseek-flash`，关闭思考模式并启用 JSON 输出，通过 Python 标准库调用，无需安装 SDK。未配置 Key、Key 无效、账户余额不足、请求繁忙和网络超时会显示对应提示，服务不会自动重试付费请求。

本机开发时，需在启动 Python 的进程环境中设置 `DEEPSEEK_API_KEY`；直接运行 `server.py` 不会读取 `.env`。如使用 Docker 备选方案，Compose 会从 `--env-file backend/.env` 传入该变量，更新后需重新创建应用容器。

接口参考：[DeepSeek 官方快速开始](https://api-docs.deepseek.com/zh-cn/)、[JSON 输出说明](https://api-docs.deepseek.com/zh-cn/guides/json_mode)。

### 答对删除与自动补充 50 道题

需要先发布新版后台，再在电视安装 0.7.0（versionCode 8）或更高版本 APK；仅更新网页不能让旧电视应用上报结果。DeepSeek Key 沿用上面的配置。

- 电视答对后立即排除该题，通过 `POST /api/v1/device/answers` 上报题目 ID 和答案；后台核对正确才删除。答错仍按原规则锁定，题目保留。设备接口无需管理员账号，管理接口仍需登录。
- 断网时，答对记录保存在电视本地，下次同步先补报，再更新题库；重复补报不会删除其他题目。电视即使重启，也不会重新抽取尚未补报的已答对题。
- 题库为空时（包括管理员删除最后一道题），后台自动生成 50 道中等难度综合题，覆盖古诗词、数学、科学、地理历史。每次补充分 5 批、每批 10 道调用模型，全部校验通过后一次入库；同批补充不会重复题干。每次补充最多产生 5 次 API 调用及对应费用。
- 补题在后台运行，同一时间只执行一轮，电视同步不等待生成。题库页面为空时每 5 秒刷新状态，完成后显示新题。生成期间家长手动加入题目时，自动生成结果不会覆盖手动题库。
- 未配置 Key 或生成失败时保留空题库并显示原因；至少等待 5 分钟后，由下一次电视同步或管理页轮询再尝试。不会回填旧的内置题目。题库为空时跳过当次答题，电视继续按每日额度计时，获得新题后恢复正常答题。

手动“AI 出题”仍需预览并确认入库；空题库的自动补题会直接入库，可在题库页面查看并删除不合适的题目。

### 电视自动同步

电视应用固定访问 `https://tvparental.mediainsight360.com/api/v1/device/state`，首次需在电视选择“绑定账号”，自动获取六位绑定码，由家长在后台输入。无需在电视配置地址或输入账号密码。绑定后使用设备凭证同步当前账号的数据，启动服务时同步，之后按后台配置的间隔轮询；操作见 [注册与绑定说明](USER_GUIDE.md#注册与绑定电视)。离线时继续使用缓存规则与题库。

从 1.0.30 开始，匿名设备同步和上报返回 401，旧版电视应用必须升级并绑定后才能在线同步。原管理员的账号密码、题库、规则和记录保留。发布前备份整个 `backend/data/`；发布后台后安装新版 APK 并绑定，最后验证手动同步与使用记录。

自动发布检查改用无需凭证的 `/api/v1/health`，只返回运行状态和版本号，不暴露题库或规则。部署流程不再使用匿名设备接口。

### 检查服务和网页

在 ECS 上执行，后台运行正常时分别返回 `active` 和 HTTP 200：

```bash
systemctl is-active tvparental
curl -sS -o /dev/null -w 'HTTP %{http_code}\n' http://127.0.0.1:8080/
curl -sS -o /dev/null -w 'HTTP %{http_code}\n' https://tvparental.mediainsight360.com/
```

上述命令使用 GET 检查后台；Python 服务目前未实现 HEAD，直接对后台使用 `curl -I` 会返回 501。本机接口正常、HTTPS 失败时检查 DNS、Caddy 和安全组。HTTPS 正常、HTTP 空白时按第 3 节配置跳转；配置后用 `curl -I http://tvparental.mediainsight360.com/` 确认 Caddy 返回 308，且 `Location` 指向 HTTPS。

### 维护与备份

- 主数据库 `backend/data/state.sqlite3` 保存账号、绑定关系和原管理员数据；其他账号的数据在 `backend/data/state.sqlite3.accounts/`。备份和恢复必须包含整个 `backend/data/` 与 `.env`。自动发布保留整个数据目录。新数据库以 `admin/admin` 初始化原管理员；新注册账号有自己的默认规则和种子题库。更新种子不会替换已有题库。
- 发布失败：先看 Actions 失败步骤，再在 ECS 执行 `sudo journalctl -u tvparental -n 100 --no-pager`。本机接口正常、HTTPS 失败时检查 DNS、Caddy 和安全组。
- 自动发布会短暂重启后台，没有自动回滚。回退时在 GitHub 撤销有问题的提交并推送到 `main`。
- 部署密钥可读写本应用代码和数据，应妥善保管；管理员密码通过交互提示输入，不要提交到 Git 或直接写进命令历史。
