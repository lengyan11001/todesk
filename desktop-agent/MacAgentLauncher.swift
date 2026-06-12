import ApplicationServices
import CoreImage
import CoreMedia
import CoreVideo
import Cocoa
import CoreGraphics
import ImageIO
@preconcurrency import ScreenCaptureKit

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var window: NSWindow!
    private var process: Process?
    private var inputPipe: Pipe?
    private let statusLabel = NSTextField(labelWithString: "未启动")
    private let idValue = NSTextField(labelWithString: "-")
    private let codeValue = NSTextField(labelWithString: "-")
    private let serverValue = NSTextField(labelWithString: "-")
    private let startButton = NSButton(title: "启动服务", target: nil, action: nil)
    private let stopButton = NSButton(title: "停止服务", target: nil, action: nil)
    private let fileListStack = NSStackView()
    private let fileEmptyLabel = NSTextField(labelWithString: "暂无收到的文件")
    private let filePathLabel = NSTextField(labelWithString: "")
    private var outputBuffer = ""
    private var dragButton: CGMouseButton = .left
    private let agentInputQueue = DispatchQueue(label: "top.bhzn.todesk.agent.input")
    private let captureQueue = DispatchQueue(label: "top.bhzn.todesk.agent.capture")
    private let frameWriteLock = NSLock()
    private let streamOutputQueue = DispatchQueue(label: "top.bhzn.todesk.agent.stream-output")
    private let ciContext = CIContext(options: nil)
    private var captureStream: SCStream?
    private var captureStreamOutput: CaptureStreamOutput?
    private var captureActive = false
    private var frameWritePending = false
    private let captureFps: Int32 = 10
    private let captureQueueDepth = 2
    private let captureMaxSide = 1280.0
    private let captureJpegQuality = 0.54
    private var permissionTimer: Timer?
    private var fileRefreshTimer: Timer?
    private var lastScreenTrusted: Bool?
    private var lastInputTrusted: Bool?

    func applicationDidFinishLaunching(_ notification: Notification) {
        stopLegacyLaunchAgent()
        buildWindow()
        loadIdentity()
        startFileRefreshTimer()
        NSApp.activate(ignoringOtherApps: true)
        if CommandLine.arguments.contains("--auto-start") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                self?.startAgent()
            }
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        permissionTimer?.invalidate()
        fileRefreshTimer?.invalidate()
        stopCaptureBridge()
        process?.terminate()
    }

    private func buildWindow() {
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 500, height: 380),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        window.center()
        window.title = "BHZN ToDesk Agent"
        window.isReleasedWhenClosed = false

        let content = NSView(frame: window.contentView!.bounds)
        content.wantsLayer = true
        content.layer?.backgroundColor = NSColor(calibratedRed: 0.957, green: 0.969, blue: 0.984, alpha: 1).cgColor
        window.contentView = content

        let card = NSView(frame: NSRect(x: 18, y: 18, width: 464, height: 344))
        card.wantsLayer = true
        card.layer?.backgroundColor = NSColor.white.cgColor
        card.layer?.cornerRadius = 8
        content.addSubview(card)

        card.addSubview(label("BHZN ToDesk Agent", x: 20, y: 294, width: 410, height: 28, size: 20, bold: true))
        card.addSubview(label("macOS 被控端", x: 20, y: 270, width: 410, height: 20, size: 13, color: .secondaryLabelColor))

        let tabs = NSTabView(frame: NSRect(x: 14, y: 12, width: 436, height: 246))
        tabs.tabViewType = .topTabsBezelBorder
        tabs.addTabViewItem(deviceTab())
        tabs.addTabViewItem(permissionTab())
        tabs.addTabViewItem(filesTab())
        card.addSubview(tabs)

        window.makeKeyAndOrderFront(nil)
    }

    private func deviceTab() -> NSTabViewItem {
        let item = NSTabViewItem(identifier: "device")
        item.label = "设备"
        let view = NSView(frame: NSRect(x: 0, y: 0, width: 436, height: 218))

        addRow(view, title: "服务器", value: serverValue, y: 166)
        addRow(view, title: "设备 ID", value: idValue, y: 122, copyButton: true)
        addRow(view, title: "验证码", value: codeValue, y: 78, copyButton: true)

        view.addSubview(label("状态", x: 18, y: 46, width: 70, height: 20, size: 13, color: .secondaryLabelColor))
        statusLabel.frame = NSRect(x: 92, y: 46, width: 300, height: 20)
        statusLabel.textColor = NSColor.systemBlue
        statusLabel.font = NSFont.boldSystemFont(ofSize: 13)
        view.addSubview(statusLabel)

        startButton.target = self
        startButton.action = #selector(startClicked)
        startButton.frame = NSRect(x: 92, y: 8, width: 108, height: 32)
        startButton.bezelStyle = .rounded
        view.addSubview(startButton)

        stopButton.target = self
        stopButton.action = #selector(stopClicked)
        stopButton.frame = NSRect(x: 212, y: 8, width: 108, height: 32)
        stopButton.bezelStyle = .rounded
        stopButton.isEnabled = false
        view.addSubview(stopButton)

        item.view = view
        return item
    }

    private func permissionTab() -> NSTabViewItem {
        let item = NSTabViewItem(identifier: "permissions")
        item.label = "权限"
        let view = NSView(frame: NSRect(x: 0, y: 0, width: 436, height: 218))

        addPermissionRow(
            view,
            title: "录屏权限",
            detail: "允许网页端查看本机屏幕",
            y: 130,
            requestAction: #selector(requestScreenPermission),
            settingsAction: #selector(openScreenSettings)
        )
        addPermissionRow(
            view,
            title: "辅助功能",
            detail: "允许 Agent 控制鼠标和键盘",
            y: 62,
            requestAction: #selector(requestAccessibilityPermission),
            settingsAction: #selector(openAccessibilitySettings)
        )

        item.view = view
        return item
    }

    private func filesTab() -> NSTabViewItem {
        let item = NSTabViewItem(identifier: "files")
        item.label = "文件"
        let view = NSView(frame: NSRect(x: 0, y: 0, width: 436, height: 218))

        view.addSubview(label("收到的文件", x: 18, y: 178, width: 96, height: 20, size: 14, bold: true))
        filePathLabel.frame = NSRect(x: 18, y: 156, width: 246, height: 18)
        filePathLabel.font = NSFont.systemFont(ofSize: 11)
        filePathLabel.textColor = .secondaryLabelColor
        filePathLabel.lineBreakMode = .byTruncatingMiddle
        view.addSubview(filePathLabel)

        let folderButton = NSButton(title: "打开文件夹", target: self, action: #selector(openReceivedFilesFolder))
        folderButton.frame = NSRect(x: 266, y: 164, width: 86, height: 28)
        folderButton.bezelStyle = .rounded
        view.addSubview(folderButton)

        let refreshButton = NSButton(title: "刷新", target: self, action: #selector(refreshReceivedFilesClicked))
        refreshButton.frame = NSRect(x: 356, y: 164, width: 56, height: 28)
        refreshButton.bezelStyle = .rounded
        view.addSubview(refreshButton)

        let scrollView = NSScrollView(frame: NSRect(x: 18, y: 12, width: 394, height: 138))
        scrollView.hasVerticalScroller = true
        scrollView.borderType = .bezelBorder

        fileListStack.orientation = .vertical
        fileListStack.alignment = .width
        fileListStack.spacing = 6
        fileListStack.edgeInsets = NSEdgeInsets(top: 6, left: 6, bottom: 6, right: 6)
        scrollView.documentView = fileListStack
        view.addSubview(scrollView)

        fileEmptyLabel.frame = NSRect(x: 34, y: 70, width: 220, height: 20)
        fileEmptyLabel.font = NSFont.systemFont(ofSize: 12)
        fileEmptyLabel.textColor = .secondaryLabelColor
        fileEmptyLabel.isHidden = true
        view.addSubview(fileEmptyLabel)

        DispatchQueue.main.async { [weak self] in
            self?.refreshReceivedFiles()
        }

        item.view = view
        return item
    }

    private func addRow(_ parent: NSView, title: String, value: NSTextField, y: CGFloat, copyButton: Bool = false) {
        parent.addSubview(label(title, x: 18, y: y + 7, width: 70, height: 20, size: 13, color: .secondaryLabelColor))
        value.frame = NSRect(x: 92, y: y, width: copyButton ? 244 : 314, height: 32)
        value.isSelectable = true
        value.backgroundColor = NSColor(calibratedRed: 0.933, green: 0.953, blue: 0.973, alpha: 1)
        value.drawsBackground = true
        value.font = NSFont.monospacedSystemFont(ofSize: 14, weight: .medium)
        parent.addSubview(value)

        if copyButton {
            let button = NSButton(title: "复制", target: self, action: #selector(copyClicked(_:)))
            button.frame = NSRect(x: 346, y: y, width: 64, height: 32)
            button.bezelStyle = .rounded
            button.identifier = NSUserInterfaceItemIdentifier(title)
            parent.addSubview(button)
        }
    }

    private func addPermissionRow(_ parent: NSView, title: String, detail: String, y: CGFloat, requestAction: Selector, settingsAction: Selector) {
        parent.addSubview(label(title, x: 18, y: y + 24, width: 100, height: 20, size: 14, bold: true))
        parent.addSubview(label(detail, x: 18, y: y + 4, width: 230, height: 20, size: 12, color: .secondaryLabelColor))
        let request = NSButton(title: "请求", target: self, action: requestAction)
        request.frame = NSRect(x: 262, y: y + 8, width: 68, height: 32)
        request.bezelStyle = .rounded
        parent.addSubview(request)

        let settings = NSButton(title: "设置", target: self, action: settingsAction)
        settings.frame = NSRect(x: 342, y: y + 8, width: 68, height: 32)
        settings.bezelStyle = .rounded
        parent.addSubview(settings)
    }

    @objc private func copyClicked(_ sender: NSButton) {
        let value = sender.identifier?.rawValue == "验证码" ? codeValue.stringValue : idValue.stringValue
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(value, forType: .string)
        statusLabel.stringValue = "已复制"
    }

    @objc private func startClicked() {
        startAgent()
    }

    @objc private func stopClicked() {
        permissionTimer?.invalidate()
        permissionTimer = nil
        stopCaptureBridge()
        process?.terminate()
        process = nil
        inputPipe = nil
        statusLabel.stringValue = "已停止"
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    @objc private func refreshReceivedFilesClicked() {
        refreshReceivedFiles()
        statusLabel.stringValue = "文件列表已刷新"
    }

    @objc private func openReceivedFilesFolder() {
        let directory = receivedFilesDirectory()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        NSWorkspace.shared.open(directory)
    }

    @objc private func openReceivedFile(_ sender: NSButton) {
        guard let url = (sender as? FileActionButton)?.fileURL else { return }
        NSWorkspace.shared.open(url)
    }

    @objc private func locateReceivedFile(_ sender: NSButton) {
        guard let url = (sender as? FileActionButton)?.fileURL else { return }
        NSWorkspace.shared.activateFileViewerSelecting([url])
    }

    private func restartAgent() {
        permissionTimer?.invalidate()
        permissionTimer = nil
        stopCaptureBridge()
        process?.terminate()
        process = nil
        inputPipe = nil
        startButton.isEnabled = true
        stopButton.isEnabled = false
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
            self?.startAgent()
        }
    }

    @objc private func requestScreenPermission() {
        if CGPreflightScreenCaptureAccess() {
            statusLabel.stringValue = "录屏权限已开启，正在刷新服务"
            restartAgent()
        } else {
            let granted = CGRequestScreenCaptureAccess()
            statusLabel.stringValue = granted ? "录屏权限已开启，正在刷新服务" : "录屏权限请求已发起"
            if granted {
                restartAgent()
            }
        }
    }

    @objc private func requestAccessibilityPermission() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        let granted = AXIsProcessTrustedWithOptions(options)
        if granted {
            statusLabel.stringValue = "辅助功能权限已开启，正在刷新服务"
            restartAgent()
        } else {
            statusLabel.stringValue = "辅助功能权限请求已发起"
        }
    }

    @objc private func openScreenSettings() {
        openPrivacySettings(anchor: "Privacy_ScreenCapture")
        statusLabel.stringValue = "已打开录屏权限设置"
    }

    @objc private func openAccessibilitySettings() {
        openPrivacySettings(anchor: "Privacy_Accessibility")
        statusLabel.stringValue = "已打开辅助功能设置"
    }

    private func openPrivacySettings(anchor: String) {
        let script = """
        tell application "System Settings"
          activate
          reveal anchor "\(anchor)" of pane id "com.apple.preference.security"
        end tell
        """
        _ = runCommand("/usr/bin/osascript", ["-e", script])
        openSettingsURL("x-apple.systempreferences:com.apple.preference.security?\(anchor)")
        openSettingsURL("x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?\(anchor)")
    }

    private func openSettingsURL(_ url: String) {
        guard let settingsURL = URL(string: url) else { return }
        NSWorkspace.shared.open(settingsURL)
    }

    private func loadIdentity() {
        let output = runAgent(["--show-id"])
        for line in output.components(separatedBy: .newlines) {
            if line.hasPrefix("设备 ID:") {
                idValue.stringValue = line.replacingOccurrences(of: "设备 ID:", with: "").trimmingCharacters(in: .whitespaces)
            } else if line.hasPrefix("验证码:") {
                codeValue.stringValue = line.replacingOccurrences(of: "验证码:", with: "").trimmingCharacters(in: .whitespaces)
            } else if line.hasPrefix("服务器:") {
                serverValue.stringValue = line.replacingOccurrences(of: "服务器:", with: "").trimmingCharacters(in: .whitespaces)
            }
        }
    }

    private func runAgent(_ arguments: [String]) -> String {
        let task = Process()
        task.executableURL = agentURL()
        task.arguments = arguments
        let pipe = Pipe()
        task.standardOutput = pipe
        task.standardError = pipe
        do {
            try task.run()
            task.waitUntilExit()
            return String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        } catch {
            return ""
        }
    }

    private func startAgent() {
        if process?.isRunning == true {
            return
        }
        stopLegacyAgentProcesses()
        let task = Process()
        task.executableURL = agentURL()
        task.arguments = ["--nogui"]
        task.environment = agentEnvironment()
        let outputPipe = Pipe()
        let inputPipe = Pipe()
        task.standardOutput = outputPipe
        task.standardError = outputPipe
        task.standardInput = inputPipe
        outputPipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            guard let text = String(data: handle.availableData, encoding: .utf8), !text.isEmpty else { return }
            self?.handleAgentOutput(text)
        }
        task.terminationHandler = { [weak self, weak task] _ in
            DispatchQueue.main.async {
                guard let self, let task, self.process === task else { return }
                self.permissionTimer?.invalidate()
                self.permissionTimer = nil
                self.stopCaptureBridge()
                self.process = nil
                self.inputPipe = nil
                self.statusLabel.stringValue = "服务已停止"
                self.startButton.isEnabled = true
                self.stopButton.isEnabled = false
            }
        }
        do {
            try task.run()
            process = task
            self.inputPipe = inputPipe
            startPermissionSync()
            statusLabel.stringValue = "正在连接服务器"
            startButton.isEnabled = false
            stopButton.isEnabled = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self, weak task] in
                guard let self, let task, self.process === task, task.isRunning else { return }
                self.statusLabel.stringValue = "在线，等待 H5 控制"
            }
        } catch {
            statusLabel.stringValue = "启动失败"
        }
    }

    private func stopLegacyLaunchAgent() {
        let uid = getuid()
        _ = runLaunchctl(["bootout", "gui/\(uid)", "\(homeDirectory())/Library/LaunchAgents/top.bhzn.todesk.agent.plist"])
        _ = runLaunchctl(["remove", "top.bhzn.todesk.agent"])
        stopLegacyAgentProcesses()
    }

    private func handleAgentOutput(_ text: String) {
        outputBuffer += text
        while let newline = outputBuffer.firstIndex(of: "\n") {
            let line = String(outputBuffer[..<newline])
            outputBuffer.removeSubrange(...newline)
            if line.hasPrefix("__BHZN_INPUT__") {
                handleInputBridge(String(line.dropFirst("__BHZN_INPUT__".count)))
                continue
            }
            if line.hasPrefix("__BHZN_CAPTURE__") {
                handleCaptureBridge(String(line.dropFirst("__BHZN_CAPTURE__".count)))
                continue
            }
            if line.hasPrefix("__BHZN_FILE_RECEIVED__") {
                handleFileReceivedBridge(String(line.dropFirst("__BHZN_FILE_RECEIVED__".count)))
                continue
            }
            DispatchQueue.main.async { [weak self] in
                if line.contains("another client replaced this device session") {
                    self?.statusLabel.stringValue = "检测到另一个客户端在线"
                } else if line.contains("[agent] connected") || line.contains("device online") {
                    self?.statusLabel.stringValue = "在线，等待 H5 控制"
                } else if line.contains("connecting") {
                    self?.statusLabel.stringValue = "正在连接服务器"
                } else if line.contains("failed") || line.contains("closed") {
                    self?.statusLabel.stringValue = "连接中"
                }
            }
        }
    }

    private func handleFileReceivedBridge(_ jsonText: String) {
        DispatchQueue.main.async { [weak self] in
            self?.refreshReceivedFiles()
            self?.statusLabel.stringValue = "已收到文件"
        }
    }

    private func startFileRefreshTimer() {
        fileRefreshTimer?.invalidate()
        fileRefreshTimer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: true) { [weak self] _ in
            self?.refreshReceivedFiles()
        }
    }

    private func refreshReceivedFiles() {
        let directory = receivedFilesDirectory()
        filePathLabel.stringValue = directory.path
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        let urls = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey, .isHiddenKey],
            options: [.skipsSubdirectoryDescendants]
        )) ?? []

        let files = urls.compactMap { url -> (url: URL, name: String, size: Int64, modified: Date)? in
            guard
                let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey, .isHiddenKey]),
                values.isRegularFile == true,
                values.isHidden != true
            else { return nil }
            let size = Int64(values.fileSize ?? 0)
            let modified = values.contentModificationDate ?? .distantPast
            return (url, url.lastPathComponent, size, modified)
        }
        .sorted { $0.modified > $1.modified }
        .prefix(50)

        for child in fileListStack.arrangedSubviews {
            fileListStack.removeArrangedSubview(child)
            child.removeFromSuperview()
        }

        fileEmptyLabel.isHidden = !files.isEmpty
        for file in files {
            fileListStack.addArrangedSubview(receivedFileRow(url: file.url, name: file.name, size: file.size, modified: file.modified))
        }

        let rowHeight: CGFloat = 52
        let totalHeight = max(138, CGFloat(files.count) * (rowHeight + 6) + 12)
        fileListStack.frame = NSRect(x: 0, y: 0, width: 392, height: totalHeight)
        fileListStack.needsLayout = true
    }

    private func receivedFileRow(url: URL, name: String, size: Int64, modified: Date) -> NSView {
        let row = NSView(frame: NSRect(x: 0, y: 0, width: 374, height: 52))
        row.wantsLayer = true
        row.layer?.backgroundColor = NSColor(calibratedRed: 0.965, green: 0.976, blue: 0.988, alpha: 1).cgColor
        row.layer?.cornerRadius = 6
        row.translatesAutoresizingMaskIntoConstraints = false
        row.heightAnchor.constraint(equalToConstant: 52).isActive = true

        let nameLabel = label(name, x: 10, y: 27, width: 220, height: 18, size: 12, bold: true)
        nameLabel.lineBreakMode = .byTruncatingMiddle
        row.addSubview(nameLabel)

        let detail = "\(formatBytes(size))  \(formatDate(modified))"
        let detailLabel = label(detail, x: 10, y: 8, width: 220, height: 16, size: 11, color: .secondaryLabelColor)
        detailLabel.lineBreakMode = .byTruncatingTail
        row.addSubview(detailLabel)

        let openButton = FileActionButton(title: "打开", target: self, action: #selector(openReceivedFile(_:)))
        openButton.frame = NSRect(x: 236, y: 12, width: 54, height: 28)
        openButton.bezelStyle = .rounded
        openButton.fileURL = url
        row.addSubview(openButton)

        let locateButton = FileActionButton(title: "定位", target: self, action: #selector(locateReceivedFile(_:)))
        locateButton.frame = NSRect(x: 300, y: 12, width: 54, height: 28)
        locateButton.bezelStyle = .rounded
        locateButton.fileURL = url
        row.addSubview(locateButton)

        return row
    }

    private func receivedFilesDirectory() -> URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Downloads", isDirectory: true)
            .appendingPathComponent("BHZN-ToDesk", isDirectory: true)
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB, .useGB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }

    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: date)
    }

    private func handleCaptureBridge(_ jsonText: String) {
        guard
            let data = jsonText.data(using: .utf8),
            let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return }
        let action = stringValue(payload["action"])
        if action == "start" {
            startCaptureBridge()
        } else if action == "stop" {
            stopCaptureBridge()
        }
    }

    private func startCaptureBridge() {
        captureQueue.async { [weak self] in
            guard let self else { return }
            if self.captureActive { return }
            self.captureActive = true
            self.setCaptureFrameWritePending(false)
            Task { [weak self] in
                await self?.startScreenCaptureStream()
            }
        }
    }

    fileprivate func stopCaptureBridge() {
        captureQueue.async { [weak self] in
            guard let self else { return }
            self.captureActive = false
            self.setCaptureFrameWritePending(false)
            let stream = self.captureStream
            self.captureStream = nil
            self.captureStreamOutput = nil
            Task {
                try? await stream?.stopCapture()
            }
        }
    }

    private func startScreenCaptureStream() async {
        guard CGPreflightScreenCaptureAccess() else {
            captureQueue.async { [weak self] in
                self?.captureActive = false
            }
            return
        }
        do {
            let shareableContent = try await SCShareableContent.current
            guard let display = selectDisplay(from: shareableContent.displays) else {
                throw NSError(domain: "top.bhzn.todesk.agent.capture", code: 1, userInfo: [NSLocalizedDescriptionKey: "no display available"])
            }
            let config = SCStreamConfiguration()
            let sourceWidth = max(1, display.width)
            let sourceHeight = max(1, display.height)
            let largestSide = Double(max(sourceWidth, sourceHeight))
            let scale = largestSide > captureMaxSide ? captureMaxSide / largestSide : 1.0
            config.width = max(1, Int(Double(sourceWidth) * scale))
            config.height = max(1, Int(Double(sourceHeight) * scale))
            config.minimumFrameInterval = CMTime(value: 1, timescale: captureFps)
            config.queueDepth = captureQueueDepth
            config.pixelFormat = kCVPixelFormatType_32BGRA
            config.scalesToFit = true
            config.showsCursor = true
            if #available(macOS 14.0, *) {
                config.preservesAspectRatio = true
            }
            let filter = SCContentFilter(display: display, excludingWindows: [])
            let output = CaptureStreamOutput(owner: self, inputWidth: sourceWidth, inputHeight: sourceHeight)
            let stream = SCStream(filter: filter, configuration: config, delegate: output)
            try stream.addStreamOutput(output, type: .screen, sampleHandlerQueue: streamOutputQueue)
            try await stream.startCapture()
            captureQueue.async { [weak self] in
                guard let self, self.captureActive else {
                    Task { try? await stream.stopCapture() }
                    return
                }
                self.captureStream = stream
                self.captureStreamOutput = output
            }
        } catch {
            DispatchQueue.main.async { [weak self] in
                self?.statusLabel.stringValue = "屏幕流启动失败"
            }
            captureQueue.async { [weak self] in
                self?.captureActive = false
            }
        }
    }

    private func selectDisplay(from displays: [SCDisplay]) -> SCDisplay? {
        let mainDisplayId = CGMainDisplayID()
        return displays.first(where: { $0.displayID == mainDisplayId }) ?? displays.first
    }

    fileprivate func handleStreamSampleBuffer(_ sampleBuffer: CMSampleBuffer, inputWidth: Int, inputHeight: Int) {
        guard captureActive, !captureFrameWriteIsPending(), sampleBuffer.isValid else { return }
        if !streamFrameIsComplete(sampleBuffer) { return }
        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let ciImage = CIImage(cvPixelBuffer: imageBuffer)
        guard let image = ciContext.createCGImage(ciImage, from: ciImage.extent) else { return }
        encodeAndSendCaptureFrame(image, inputWidth: inputWidth, inputHeight: inputHeight)
    }

    private func streamFrameIsComplete(_ sampleBuffer: CMSampleBuffer) -> Bool {
        guard
            let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
            let first = attachments.first,
            let rawStatus = first[.status] as? Int,
            let status = SCFrameStatus(rawValue: rawStatus)
        else { return true }
        return status == .complete || status == .started
    }

    private func encodeAndSendCaptureFrame(_ image: CGImage, inputWidth sourceInputWidth: Int? = nil, inputHeight sourceInputHeight: Int? = nil) {
        let imageWidth = image.width
        let imageHeight = image.height
        let largestSide = Double(max(imageWidth, imageHeight))
        let scale = largestSide > captureMaxSide ? captureMaxSide / largestSide : 1.0
        let outputWidth = max(1, Int(Double(imageWidth) * scale))
        let outputHeight = max(1, Int(Double(imageHeight) * scale))
        guard let scaled = scaledImage(image, width: outputWidth, height: outputHeight),
              let jpeg = jpegData(scaled, quality: captureJpegQuality)
        else { return }
        let payload = [
            "width": outputWidth,
            "height": outputHeight,
            "inputWidth": sourceInputWidth ?? imageWidth,
            "inputHeight": sourceInputHeight ?? imageHeight,
            "bytes": jpeg.count,
            "timestamp": Int(Date().timeIntervalSince1970 * 1000)
        ] as [String: Any]
        writeCaptureFrameInput(header: payload, jpeg: jpeg)
    }

    private func scaledImage(_ image: CGImage, width: Int, height: Int) -> CGImage? {
        if image.width == width && image.height == height {
            return image
        }
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return nil }
        context.interpolationQuality = .medium
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return context.makeImage()
    }

    private func jpegData(_ image: CGImage, quality: Double) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil) else {
            return nil
        }
        let options = [kCGImageDestinationLossyCompressionQuality as String: quality] as CFDictionary
        CGImageDestinationAddImage(destination, image, options)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }

    private func writeAgentInput(_ text: String) {
        guard let data = text.data(using: .utf8) else { return }
        agentInputQueue.async { [weak self] in
            guard let self, let handle = self.inputPipe?.fileHandleForWriting else { return }
            do {
                try handle.write(contentsOf: data)
            } catch {
                self.stopCaptureBridge()
            }
        }
    }

    private func captureFrameWriteIsPending() -> Bool {
        frameWriteLock.lock()
        let pending = frameWritePending
        frameWriteLock.unlock()
        return pending
    }

    private func setCaptureFrameWritePending(_ pending: Bool) {
        frameWriteLock.lock()
        frameWritePending = pending
        frameWriteLock.unlock()
    }

    private func writeCaptureFrameInput(header: [String: Any], jpeg: Data) {
        guard
            let headerData = try? JSONSerialization.data(withJSONObject: header),
            let headerText = String(data: headerData, encoding: .utf8)
        else { return }
        let line = "__BHZN_FRAME_BYTES__" + headerText + "\n"
        guard let lineData = line.data(using: .utf8) else { return }
        frameWriteLock.lock()
        if frameWritePending {
            frameWriteLock.unlock()
            return
        }
        frameWritePending = true
        frameWriteLock.unlock()
        agentInputQueue.async { [weak self] in
            defer { self?.setCaptureFrameWritePending(false) }
            guard let self, let handle = self.inputPipe?.fileHandleForWriting else { return }
            do {
                try handle.write(contentsOf: lineData)
                try handle.write(contentsOf: jpeg)
            } catch {
                self.stopCaptureBridge()
            }
        }
    }

    private func startPermissionSync() {
        lastScreenTrusted = nil
        lastInputTrusted = nil
        sendPermissionState(force: true)
        permissionTimer?.invalidate()
        permissionTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in
            self?.sendPermissionState(force: false)
        }
    }

    private func sendPermissionState(force: Bool) {
        let screenTrusted = CGPreflightScreenCaptureAccess()
        let inputTrusted = AXIsProcessTrusted()
        if !force && lastScreenTrusted == screenTrusted && lastInputTrusted == inputTrusted {
            return
        }
        lastScreenTrusted = screenTrusted
        lastInputTrusted = inputTrusted
        let payload: [String: Any] = ["screen": screenTrusted, "input": inputTrusted]
        guard
            let data = try? JSONSerialization.data(withJSONObject: payload),
            let text = String(data: data, encoding: .utf8)
        else { return }
        writeAgentInput("__BHZN_PERMISSION__" + text + "\n")
    }

    private func handleInputBridge(_ jsonText: String) {
        guard
            let data = jsonText.data(using: .utf8),
            let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return }
        executeInput(payload)
    }

    private func executeInput(_ payload: [String: Any]) {
        let action = stringValue(payload["action"])
        let x = doubleValue(payload["x"])
        let y = doubleValue(payload["y"])
        let x2 = doubleValue(payload["x2"])
        let y2 = doubleValue(payload["y2"])
        let duration = max(0.01, min(5.0, doubleValue(payload["durationMs"]) / 1000.0))
        let button = mouseButton(stringValue(payload["button"]))

        switch action {
        case "tap":
            click(x: x, y: y, button: button)
        case "doubleTap":
            click(x: x, y: y, button: button)
            usleep(60000)
            click(x: x, y: y, button: button)
        case "rightClick":
            click(x: x, y: y, button: .right)
        case "swipe", "homeSwipe":
            moveMouse(x: x, y: y)
            mouseDown(x: x, y: y, button: button)
            dragMouse(fromX: x, fromY: y, toX: x2, toY: y2, duration: duration, button: button)
            mouseUp(x: x2, y: y2, button: button)
        case "dragStart":
            dragButton = button
            moveMouse(x: x, y: y)
            mouseDown(x: x, y: y, button: button)
        case "dragMove":
            dragMouse(fromX: x, fromY: y, toX: x, toY: y, duration: 0.01, button: dragButton)
        case "dragEnd":
            moveMouse(x: x, y: y)
            mouseUp(x: x, y: y, button: dragButton)
            dragButton = .left
        case "scroll":
            scroll(deltaY: intValue(payload["deltaY"]))
        case "back":
            hotkey(["command", "["])
        case "home":
            hotkey(["command", "space"])
        case "key":
            let key = stringValue(payload["key"])
            let modifiers = payload["modifiers"] as? [String] ?? []
            pressKey(key, modifiers: modifiers)
        case "text":
            let text = stringValue(payload["text"])
            if !text.isEmpty { pasteText(text) }
        default:
            break
        }
    }

    private func moveMouse(x: Double, y: Double) {
        let point = CGPoint(x: x, y: y)
        CGWarpMouseCursorPosition(point)
        CGAssociateMouseAndMouseCursorPosition(boolean_t(1))
    }

    private func click(x: Double, y: Double, button: CGMouseButton) {
        moveMouse(x: x, y: y)
        mouseDown(x: x, y: y, button: button)
        usleep(50000)
        mouseUp(x: x, y: y, button: button)
    }

    private func mouseDown(x: Double, y: Double, button: CGMouseButton) {
        postMouse(button == .right ? .rightMouseDown : .leftMouseDown, x: x, y: y, button: button)
    }

    private func mouseUp(x: Double, y: Double, button: CGMouseButton) {
        postMouse(button == .right ? .rightMouseUp : .leftMouseUp, x: x, y: y, button: button)
    }

    private func dragMouse(fromX: Double, fromY: Double, toX: Double, toY: Double, duration: Double, button: CGMouseButton) {
        let steps = max(1, min(24, Int(duration / 0.015)))
        let eventType: CGEventType = button == .right ? .rightMouseDragged : .leftMouseDragged
        for step in 1...steps {
            let ratio = Double(step) / Double(steps)
            let x = fromX + (toX - fromX) * ratio
            let y = fromY + (toY - fromY) * ratio
            postMouse(eventType, x: x, y: y, button: button)
            usleep(15000)
        }
    }

    private func postMouse(_ type: CGEventType, x: Double, y: Double, button: CGMouseButton) {
        let event = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: CGPoint(x: x, y: y), mouseButton: button)
        event?.post(tap: .cghidEventTap)
    }

    private func scroll(deltaY: Int) {
        let clamped = Int32(max(-10, min(10, -deltaY)))
        let event = CGEvent(scrollWheelEvent2Source: nil, units: .line, wheelCount: 1, wheel1: clamped, wheel2: 0, wheel3: 0)
        event?.post(tap: .cghidEventTap)
    }

    private func pasteText(_ text: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        hotkey(["command", "v"])
    }

    private func pressKey(_ key: String, modifiers: [String]) {
        guard let keyCode = keyCode(key) else { return }
        let flags = modifierFlags(modifiers)
        postKey(keyCode, down: true, flags: flags)
        postKey(keyCode, down: false, flags: flags)
    }

    private func hotkey(_ keys: [String]) {
        guard let key = keys.last, let keyCode = keyCode(key) else { return }
        let flags = modifierFlags(Array(keys.dropLast()))
        postKey(keyCode, down: true, flags: flags)
        postKey(keyCode, down: false, flags: flags)
    }

    private func postKey(_ keyCode: CGKeyCode, down: Bool, flags: CGEventFlags) {
        let event = CGEvent(keyboardEventSource: nil, virtualKey: keyCode, keyDown: down)
        event?.flags = flags
        event?.post(tap: .cghidEventTap)
    }

    private func mouseButton(_ value: String) -> CGMouseButton {
        value == "right" ? .right : .left
    }

    private func modifierFlags(_ values: [String]) -> CGEventFlags {
        var flags = CGEventFlags()
        for value in values.map({ $0.lowercased() }) {
            if value == "command" || value == "cmd" || value == "meta" {
                flags.insert(.maskCommand)
            } else if value == "ctrl" || value == "control" {
                flags.insert(.maskControl)
            } else if value == "alt" || value == "option" {
                flags.insert(.maskAlternate)
            } else if value == "shift" {
                flags.insert(.maskShift)
            }
        }
        return flags
    }

    private func keyCode(_ key: String) -> CGKeyCode? {
        let value = key.lowercased()
        let map: [String: CGKeyCode] = [
            "a": 0, "s": 1, "d": 2, "f": 3, "h": 4, "g": 5, "z": 6, "x": 7,
            "c": 8, "v": 9, "b": 11, "q": 12, "w": 13, "e": 14, "r": 15,
            "y": 16, "t": 17, "1": 18, "2": 19, "3": 20, "4": 21, "6": 22,
            "5": 23, "=": 24, "9": 25, "7": 26, "-": 27, "8": 28, "0": 29,
            "]": 30, "o": 31, "u": 32, "[": 33, "i": 34, "p": 35,
            "enter": 36, "return": 36, "l": 37, "j": 38, "'": 39, "k": 40,
            ";": 41, "\\": 42, ",": 43, "/": 44, "n": 45, "m": 46, ".": 47,
            "tab": 48, "space": 49, "delete": 51, "backspace": 51, "escape": 53,
            "left": 123, "right": 124, "down": 125, "up": 126
        ]
        return map[value]
    }

    private func stringValue(_ value: Any?) -> String {
        value as? String ?? ""
    }

    private func doubleValue(_ value: Any?) -> Double {
        if let number = value as? NSNumber { return number.doubleValue }
        if let string = value as? String { return Double(string) ?? 0 }
        return 0
    }

    private func intValue(_ value: Any?) -> Int {
        if let number = value as? NSNumber { return number.intValue }
        if let string = value as? String { return Int(string) ?? 0 }
        return 0
    }

    private func stopLegacyAgentProcesses() {
        _ = runCommand("/usr/bin/pkill", ["-f", "\(homeDirectory())/Applications/BHZN ToDesk Agent.app/Contents/MacOS/BHZN ToDesk Agent --nogui"])
        _ = runCommand("/usr/bin/pkill", ["-f", "\(homeDirectory())/Applications/BHZN-ToDesk-Agent/run-macos.sh"])
        _ = runCommand("/usr/bin/pkill", ["-f", "agent-bin/BHZN ToDesk Agent.app/Contents/MacOS/BHZN ToDesk Agent --nogui"])
        _ = runCommand("/usr/bin/pkill", ["-f", "agent-bin/bhzn-agent-helper/bhzn-agent-helper --nogui"])
        _ = runCommand("/usr/bin/pkill", ["-f", "Contents/MacOS/agent-bin/bhzn-agent-helper --nogui"])
        _ = runCommand("/usr/bin/pkill", ["-f", "BHZN ToDesk Helper.app/Contents/MacOS/bhzn-agent-helper"])
        _ = runCommand("/usr/bin/pkill", ["-f", "BHZN ToDesk Helper.app/Contents/MacOS/BHZN ToDesk Helper"])
    }

    private func runLaunchctl(_ arguments: [String]) -> Int32 {
        runCommand("/bin/launchctl", arguments)
    }

    private func runCommand(_ path: String, _ arguments: [String]) -> Int32 {
        let task = Process()
        task.executableURL = URL(fileURLWithPath: path)
        task.arguments = arguments
        task.standardOutput = Pipe()
        task.standardError = Pipe()
        do {
            try task.run()
            task.waitUntilExit()
            return task.terminationStatus
        } catch {
            return -1
        }
    }

    private func agentEnvironment() -> [String: String] {
        var environment = ProcessInfo.processInfo.environment
        environment["BHZN_MAC_INPUT_BRIDGE"] = "1"
        environment["BHZN_MAC_INPUT_TRUSTED"] = AXIsProcessTrusted() ? "1" : "0"
        environment["BHZN_MAC_CAPTURE_BRIDGE"] = "1"
        environment["BHZN_MAC_SCREEN_TRUSTED"] = CGPreflightScreenCaptureAccess() ? "1" : "0"
        return environment
    }

    private func homeDirectory() -> String {
        FileManager.default.homeDirectoryForCurrentUser.path
    }

    private func agentURL() -> URL {
        Bundle.main.bundleURL
            .appendingPathComponent("Contents")
            .appendingPathComponent("Resources")
            .appendingPathComponent("agent-bin")
            .appendingPathComponent("bhzn-agent-helper")
    }

    private func label(_ text: String, x: CGFloat, y: CGFloat, width: CGFloat, height: CGFloat, size: CGFloat, bold: Bool = false, color: NSColor = .labelColor) -> NSTextField {
        let field = NSTextField(labelWithString: text)
        field.frame = NSRect(x: x, y: y, width: width, height: height)
        field.font = bold ? NSFont.boldSystemFont(ofSize: size) : NSFont.systemFont(ofSize: size)
        field.textColor = color
        return field
    }
}

final class FileActionButton: NSButton {
    var fileURL: URL?
}

final class CaptureStreamOutput: NSObject, SCStreamOutput, SCStreamDelegate {
    private weak var owner: AppDelegate?
    private let inputWidth: Int
    private let inputHeight: Int

    init(owner: AppDelegate, inputWidth: Int, inputHeight: Int) {
        self.owner = owner
        self.inputWidth = inputWidth
        self.inputHeight = inputHeight
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of outputType: SCStreamOutputType) {
        guard outputType == .screen else { return }
        owner?.handleStreamSampleBuffer(sampleBuffer, inputWidth: inputWidth, inputHeight: inputHeight)
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        owner?.stopCaptureBridge()
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
