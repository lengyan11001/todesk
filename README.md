# BHZN ToDesk

独立远程控制产品雏形，包含服务端/H5、Android 被控端、Windows/macOS 桌面被控端。

## 目录

- `server/`: Node.js 设备中继、H5 控制台、CMS 审核后台，默认端口 `38080`。
- `android/`: Android APK，展示设备 ID/验证码，申请录屏和无障碍控制权限。
- `desktop-agent-rs/`: Windows Rust 桌面 Agent，支持安装、自启、自动升级。
- `desktop-agent/`: Python/macOS 内测 Agent 和 macOS 打包脚本。

默认线上地址：`https://todesk.bhzn.top`

## 当前包

- Android APK: `E:\BHZN-ToDesk\android\manual-build\out\bhzn-todesk-debug-v0.1.2-3.apk`
- H5 Android 下载副本: `E:\BHZN-ToDesk\server\public\downloads\bhzn-todesk-debug.apk`
- Windows Agent: `E:\BHZN-ToDesk\desktop-agent-rs\dist\BHZN-ToDesk-Agent.exe`
- Windows 安装包: `E:\BHZN-ToDesk\desktop-agent-rs\dist\BHZN-ToDesk-Agent-Setup.exe`
- H5 Windows 下载副本: `E:\BHZN-ToDesk\server\public\downloads\BHZN-ToDesk-Agent.exe`
- macOS Agent 无签名内测包: `E:\BHZN-ToDesk\server\public\downloads\BHZN-ToDesk-Agent-mac.zip`

## Android 打包

```powershell
cd E:\BHZN-ToDesk\android
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -Channel debug -VersionCode 3 -VersionName 0.1.2
```

release 覆盖升级必须一直使用同一个 keystore，见 `android/RELEASE_SIGNING.md`。

## Windows Agent 安装/运行

```powershell
cd E:\BHZN-ToDesk\desktop-agent-rs
powershell -ExecutionPolicy Bypass -File .\build-windows.ps1
.\dist\BHZN-ToDesk-Agent-Setup.exe
```

Setup 会安装到 `%LOCALAPPDATA%\BHZN-ToDesk`，注册当前用户开机自启和 4 小时一次的更新检查。自动更新读取服务端 `/api/releases/windows-agent`，下载后校验 SHA256 再替换。

## Windows Agent 手动运行

```powershell
cd E:\BHZN-ToDesk\desktop-agent-rs
.\dist\BHZN-ToDesk-Agent.exe --no-update
```

## macOS Agent 无开发者账号内测安装

```bash
unzip BHZN-ToDesk-Agent-mac.zip
cd BHZN-ToDesk-Agent-mac
bash install-macos.sh
```

macOS 首次运行后，需要在系统设置里给 Terminal 或 Python 授权：

- Screen Recording
- Accessibility
- Input Monitoring

授权完成后重启 Agent，再在 H5 输入设备 ID 和验证码绑定。
