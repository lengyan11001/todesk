BHZN ToDesk Agent 0.2.12 更新包
================================

更新内容：
- RTC 路径不再重复 JPEG 编码，降低 CPU
- 去掉每帧 BGRA 拷贝
- 修正帧节拍，实际帧率更接近目标
- 支持鼠标悬停移动（move）
- 2K 档位：平衡/高清/局域网支持 2560 宽
- 更新重启时保留 --config，设备号不丢
- 安装时注册每 4 小时自动更新任务

使用方法：
1. 把本目录放到远程机器上
2. 双击 install_update.bat（或右键 install_update.ps1 用 PowerShell 运行）
3. 脚本会自动停止旧 agent、备份、替换、重启，并注册自动更新任务

如果提示没有权限，请用管理员 PowerShell 运行 install_update.ps1。

回滚：
把同目录生成的 BHZN-ToDesk-Agent.exe.bak_日期时间 改回 BHZN-ToDesk-Agent.exe，再重启 agent。
