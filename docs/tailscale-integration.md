# 设备网络与永久绑定

当前版本同时支持内置 Tailscale 网络与 Windows 外部 Tailscale 兼容方式。临时 HTTPS 连接独立使用 Cloudflare Quick Tunnel，不需要 Tailscale。

## 内置网络

Windows 助手和 Android App 内置基于 `tsnet` 的网络组件。两端分别登录同一 Tailscale 网络后，可通过私有地址建立永久连接，无需另装 Tailscale App、配置域名或路由器端口转发。仍依赖 Tailscale 账号、控制平面以及必要时的 DERP 中继。

1. 在电脑助手设置中登录内置设备网络。
2. 在手机“设置 → 设备网络”中登录同一网络。
3. 等待网络就绪，电脑选择“Tailscale 永久绑定”，手机扫描对应二维码。

网络登录过程中会显示状态与登录链接；成功登录不等于已经配对，仍需扫码保存访问凭据。

## 身份与地址

内置节点是独立身份，与系统 Tailscale 节点不同。从外部 Tailscale 切换到内置网络后，重新扫描永久二维码。正常重启会保留网络身份；移除身份、注销节点或删除应用数据后需要重新登录和配对。

永久入口使用 `http://100.x.x.x:18787` 形式，流量通过 Tailscale 的 WireGuard 隧道加密。它只在私有网络内开放，不启用 Funnel，也不要求配置公网 HTTPS 域名。ACL、防火墙和电脑休眠仍会影响可达性。

Windows 内置组件固定运行于 `%LOCALAPPDATA%\CodingRemoteAssistant\bin\tailnet.exe`，首次可能需要防火墙授权。固定路径使后续启动能够复用规则。

## 外部 Tailscale 兼容方式

Windows 的 `coding_remote/tailscale_link.py` 使用系统 Tailscale 的私有 IPv4 建立监听，默认固定端口 18787，转发到本机 API。它不修改其他应用的 Serve 配置；旧助手占用端口时应先退出旧实例。

## 凭据与重连

- 永久二维码 5 分钟有效、单次使用，配对凭据持久保存并可撤销。
- 临时与永久线路分别保存，扫描临时二维码不会删除永久绑定。
- Android 使用 Keystore 加密连接凭据；Windows 保存稳定电脑身份、设置及请求收据。
- 读请求和写入前的连通检查支持在同一电脑已保存的线路间切换；业务写入实际发出后不会自动重放。

关闭 Windows 窗口保留后台网络服务；退出后台服务、电脑睡眠或关机后手机无法访问。

## 开发入口

Go 源码与固定依赖位于 `packaging/tailnet/`，由 `packaging/build_tailnet.py` 生成 Windows x64、Android ARM64 / x86_64 组件。Windows 管理由 `coding_remote/embedded_network.py` 实现，Android 集成位于 `packaging/android/native/EmbeddedNetwork.java`。构建要求见[构建说明](native-build.md)，第三方声明见[网络组件声明](../packaging/tailnet/THIRD_PARTY_NOTICES.txt)。
