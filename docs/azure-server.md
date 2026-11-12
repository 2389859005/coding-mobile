# Azure 自有服务器永久绑定

适用于 2.2.5。Azure VM 提供固定 HTTPS 入口，Windows 助手通过反向 SSH 隧道把本地 API 转发到服务器。手机使用 HTTPS 和扫码凭据；任务仍在 Windows 上执行，电脑与助手需要保持运行。

```text
Android ── HTTPS / SSE ──> Azure Nginx (443)
                              │
                        127.0.0.1:18787
                              │ 反向 SSH 隧道
                              └──────────> Windows 助手本地 API
```

HTTPS 在自己的 VM 上终止，VM 管理员有能力访问转发的数据。服务器无需保存项目、桌面客户端或 SSH 私钥；私钥只留在 Windows。

## 1. 准备 VM 和 DNS

使用 Ubuntu / Debian，为静态公网 IP 设置 Azure DNS 名称标签，得到 `your-label.region.cloudapp.azure.com`，或使用自己的域名。以下示例均需替换域名和用户名。

Azure 入站规则允许 TCP 80、443；SSH 22 应允许电脑所在网络连接。远端转发端口 18787 仅监听服务器回环地址，无需放行到公网。服务器本机若启用了防火墙，也需允许以上所需端口。

在 Windows `%USERPROFILE%/.ssh/config` 中增加（保留已有配置）：

```sshconfig
Host coding-remote-server
    HostName your-label.region.cloudapp.azure.com
    User your-user
    IdentityFile D:/keys/server.pem
    IdentitiesOnly yes
    StrictHostKeyChecking yes
    ServerAliveInterval 15
    ServerAliveCountMax 3
```

首次连接时核对并保存服务器 SSH 主机指纹。Windows OpenSSH 需要私钥 ACL 仅允许本人及必要的系统账户读取。确认 `ssh -o BatchMode=yes coding-remote-server` 能免交互登录；助手使用系统 OpenSSH 和此别名。

SSH 服务需允许远端 TCP 转发（`AllowTcpForwarding yes` 或 `remote`）；保持 `GatewayPorts no`。助手自动管理 `-R 127.0.0.1:18787:127.0.0.1:<本地实际端口>`，不要同时手动启动占用 18787 的转发。

## 2. 安装 HTTPS 反向代理

在 VM 执行：

```bash
sudo apt update
sudo apt install -y nginx certbot python3-certbot-nginx
```

创建 `/etc/nginx/sites-available/coding-remote`，替换域名：

```nginx
server {
    listen 80;
    server_name your-label.region.cloudapp.azure.com;
    server_tokens off;
    access_log off;
    client_max_body_size 5m;

    location = /healthz {
        default_type application/json;
        return 200 '{"service":"coding-remote-relay","status":"ok"}';
    }
    location / {
        proxy_pass http://127.0.0.1:18787;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Connection "";
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
        proxy_cache off;
        proxy_next_upstream off;
        gzip off;
        proxy_connect_timeout 5s;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```

`proxy_buffering off` 是 SSE 及时推送的关键。保留 Host 让助手正确验证公网入口，禁止代理自动重试避免重复写请求。无需额外配置 WebSocket。

```bash
sudo ln -s /etc/nginx/sites-available/coding-remote /etc/nginx/sites-enabled/coding-remote
sudo nginx -t
sudo systemctl reload nginx
sudo certbot --nginx -d your-label.region.cloudapp.azure.com --redirect
sudo systemctl enable --now certbot.timer
sudo certbot renew --dry-run
```

按 Certbot 提示完成证书签发。HTTP 80 保留给跳转及证书续期；手机连接使用 HTTPS 443。

## 3. 连接项目

1. 打开新版 Windows 助手，“应用设置 → 自有服务器永久绑定”。
2. HTTPS 地址填 `https://your-label.region.cloudapp.azure.com`，不带路径；SSH 主机填 `coding-remote-server`；远端端口填 `18787`。
3. 点击“保存并连接”。助手建立 SSH 隧道并检查 HTTPS 返回的是当前电脑身份；通过后生成永久绑定二维码。
4. Android 安装配套版本，在 App 内扫码。二维码 5 分钟有效且单次使用，配对后凭据持久保存，可从电脑撤销。

配置保存在当前 Windows 用户的 `%LOCALAPPDATA%/CodingRemoteAssistant/settings.json`；域名、SSH 别名和私钥不写入安装包默认值。开启自动连接后重新打开助手会恢复入口；SSH 中断会自动重试。关闭入口或电脑休眠期间不能访问，永久凭据仍可在恢复后使用。

## 检查与排障

- `https://域名/healthz` 正常只表示 Nginx 在线；`https://域名/api/gateway` 返回 `service: coding-remote`、当前版本和电脑身份，才说明整条转发路径可用。
- 业务路由返回 502：检查助手是否运行、`ssh-tunnel-server.log`、远端 18787 是否被其他进程占用。
- 外网超时：检查 DNS、公网 IP、Azure 入站规则与本机防火墙。仅测试 VM 本机 HTTP 无法确认公网连通。
- HTTPS 探测与浏览器结果不同：助手遵守系统代理配置；可比较系统代理与直连结果，避免误判服务器故障。
- 会话仍不更新：两端都升级到 2.2.5，并确认代理关闭缓冲。新版本依靠源事件触发更新，保活帧只检测断线。
- 后台悬浮窗：不显示常驻监控通知；Android 省电或强制停止可能终止后台进程，回到 App 可恢复。

日常可用 `sudo nginx -t`、`systemctl status nginx`、`systemctl list-timers certbot.timer` 和 `sudo certbot renew --dry-run` 检查服务器。一个 18787 转发端口对应一台电脑；多电脑需要分别配置域名入口及端口。

## 本次 Azure 入口修复

原入口 `mmm0516.eastasia.cloudapp.azure.com` 在本次手机 App 与电脑直连测试中出现 TLS 连接重置，而浏览器可打开。改用同一 Azure 静态 IP 的 HTTPS 域名 `mmm0516.104.214.184.152.sslip.io`，手机原生连接已通过正常证书校验并读取 `/healthz`。sslip.io 仅提供 DNS 解析，HTTPS 流量仍到自己的 VM；该域名依赖 IP 保持不变及 sslip.io DNS，可替换成自己的域名。

Windows 自有服务器设置使用新 HTTPS 地址，SSH 别名仍为 `coding-remote-azure`、远端端口 `18787`，保存并连接后用新版 Android 重新扫码。旧二维码不会自动改成新地址。原入口保留。`/healthz` 正常而 `/api/gateway` 返回 502 时，需要先启动电脑助手的反向隧道。
