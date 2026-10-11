# SSH Term

极简 Android SSH 终端（Kotlin + JSch）。密码登录，PTY shell，支持键盘输入与 Tab/Esc/Ctrl-C/方向键。

## 获取 APK
- GitHub → Actions → "Build APK" → 下载 `ssh-term-debug-apk`，手机安装（需允许未知来源）。
- 或 Android Studio 打开本目录，Run。

## 使用
填写 Ubuntu 的 IP / 端口 / 用户 / 密码 → 连接。Ubuntu 需已装 `openssh-server`（`sudo apt install openssh-server`）。
家庭网络外访问需 VPN（如 Tailscale）或路由器端口转发，不建议直接暴露 22 端口。

## 限制
非完整终端模拟：vim/top/htop 等全屏程序显示不正常；不校验主机密钥；仅密码认证。
