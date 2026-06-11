# BHZN ToDesk Desktop Agent

Windows/macOS 被控端 Agent，接入现有 H5 控制台。

默认直接启动会显示设备 ID、验证码和连接状态窗口；后台/自启动模式使用 `--nogui`。

## Windows 运行

```powershell
cd E:\BHZN-ToDesk\desktop-agent
powershell -ExecutionPolicy Bypass -File .\run-windows.ps1
```

## macOS 无开发者账号内测安装

```bash
cd desktop-agent
bash install-macos.sh
```

安装后会注册用户级 LaunchAgent，随登录自动启动。这个包没有 Apple Developer ID 签名和公证，适合内测。macOS 首次运行需要在系统设置里给 Terminal 或 Python 开启：

- Screen Recording
- Accessibility
- Input Monitoring（部分键盘场景需要）

卸载：

```bash
bash ~/Applications/BHZN-ToDesk-Agent/uninstall-macos.sh
```

生成无签名内测 zip：

```bash
chmod +x build-macos-unsigned-package.sh
./build-macos-unsigned-package.sh
```

## 设备绑定

启动后终端会显示设备 ID 和验证码，在 H5 登录后输入这两个值即可添加。

配置文件保存位置：

- Windows: `%APPDATA%\BHZN-ToDesk\agent.json`
- macOS: `~/Library/Application Support/BHZN-ToDesk/agent.json`

## Windows 打包 exe

```powershell
cd E:\BHZN-ToDesk\desktop-agent
powershell -ExecutionPolicy Bypass -File .\build-windows.ps1
```

输出：`desktop-agent/dist/BHZN-ToDesk-Agent.exe`

## macOS 可信签名包

需要在 macOS 上执行，并且 Keychain 里已经安装 Developer ID Application 和 Developer ID Installer 证书。

```bash
export DEVELOPER_ID_APPLICATION="Developer ID Application: Company Name (TEAMID)"
export DEVELOPER_ID_INSTALLER="Developer ID Installer: Company Name (TEAMID)"
export APPLE_TEAM_ID="TEAMID"
export APPLE_ID="apple-id@example.com"
export APPLE_APP_PASSWORD="xxxx-xxxx-xxxx-xxxx"

chmod +x build-macos-signed.sh
./build-macos-signed.sh
```

输出：`desktop-agent/dist-macos/BHZN ToDesk Agent-0.1.0.pkg`
