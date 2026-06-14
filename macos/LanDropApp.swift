import AppKit
import Darwin
import Foundation
import WebKit

final class AppDelegate: NSObject, NSApplicationDelegate, WKNavigationDelegate {
    private var window: NSWindow?
    private var webView: WKWebView?
    private var serverProcess: Process?
    private var selectedPort: Int = 4318
    private var serverReady = false
    private let logFile = "/tmp/lan-drop-mac-app.log"

    private var projectRoot: String {
        if let explicitRoot = ProcessInfo.processInfo.environment["LAN_DROP_PROJECT_ROOT"], !explicitRoot.isEmpty {
            return explicitRoot
        }
        if
            let rootFile = Bundle.main.url(forResource: "project-root", withExtension: "txt"),
            let root = try? String(contentsOf: rootFile, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines),
            !root.isEmpty
        {
            return root
        }
        return Bundle.main.bundleURL.deletingLastPathComponent().deletingLastPathComponent().path
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        do {
            log("App launch")
            selectedPort = try findOpenPort(in: 4318...4399)
            log("Selected port \(selectedPort)")
            try startServer()
            createWindow()
            waitForServerAndLoad()
        } catch {
            log("Fatal startup error: \(error.localizedDescription)")
            showFatalError(message: "LAN Drop 启动失败：\(error.localizedDescription)")
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        if let process = serverProcess, process.isRunning {
            log("Terminating child server")
            process.terminate()
        }
    }

    private func createWindow() {
        let config = WKWebViewConfiguration()
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1280, height: 860),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.center()
        window.title = "LAN Drop"
        window.contentView = webView
        window.makeKeyAndOrderFront(nil)

        self.window = window
        self.webView = webView
        NSApp.activate(ignoringOtherApps: true)
        log("Window created")
    }

    private func startServer() throws {
        let nodePath = try resolveNodePath()
        let process = Process()
        process.executableURL = URL(fileURLWithPath: nodePath)
        process.currentDirectoryURL = URL(fileURLWithPath: projectRoot)
        process.arguments = ["server.js"]
        log("Using node path \(nodePath)")

        var environment = ProcessInfo.processInfo.environment
        environment["PORT"] = String(selectedPort)
        environment["HOST"] = "0.0.0.0"
        environment["LAN_DROP_CODE"] = "off"
        process.environment = environment

        let logPipe = Pipe()
        process.standardOutput = logPipe
        process.standardError = logPipe
        logPipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            guard !data.isEmpty, let text = String(data: data, encoding: .utf8) else { return }
            self?.log("server: \(text.trimmingCharacters(in: .whitespacesAndNewlines))")
        }

        try process.run()
        process.terminationHandler = { [weak self] process in
            self?.log("Server exited with status \(process.terminationStatus)")
            if self?.serverReady == false {
                DispatchQueue.main.async {
                    self?.showFatalError(message: "LAN Drop 服务启动后立即退出了，请检查 Node 环境或端口占用。")
                }
            }
        }
        self.serverProcess = process
        log("Child server launched")
    }

    private func waitForServerAndLoad() {
        let statusURL = URL(string: "http://127.0.0.1:\(selectedPort)/api/status")!
        let landingURL = URL(string: "http://127.0.0.1:\(selectedPort)")!
        let deadline = Date().addingTimeInterval(15)

        func poll() {
            var request = URLRequest(url: statusURL)
            request.timeoutInterval = 1
            URLSession.shared.dataTask(with: request) { [weak self] _, response, _ in
                guard let self else { return }
                let ok = (response as? HTTPURLResponse)?.statusCode == 200
                DispatchQueue.main.async {
                    if ok {
                        self.serverReady = true
                        self.log("Server ready on \(landingURL.absoluteString)")
                        self.webView?.load(URLRequest(url: landingURL))
                    } else if Date() < deadline {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35, execute: poll)
                    } else {
                        self.log("Server polling timed out")
                        self.showFatalError(message: "LAN Drop 服务未能在 15 秒内启动。")
                    }
                }
            }.resume()
        }

        poll()
    }

    private func resolveNodePath() throws -> String {
        let candidates = [
            "/opt/homebrew/bin/node",
            "/usr/local/bin/node"
        ]
        if let found = candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0) }) {
            return found
        }
        throw NSError(domain: "LanDrop", code: 1, userInfo: [
            NSLocalizedDescriptionKey: "没有找到可用的 Node.js，可先安装或修复 Node 环境。"
        ])
    }

    private func findOpenPort(in range: ClosedRange<Int>) throws -> Int {
        for port in range {
            let fd = socket(AF_INET, SOCK_STREAM, 0)
            if fd < 0 { continue }

            var address = sockaddr_in()
            address.sin_len = UInt8(MemoryLayout<sockaddr_in>.stride)
            address.sin_family = sa_family_t(AF_INET)
            address.sin_port = in_port_t(UInt16(port).bigEndian)
            address.sin_addr = in_addr(s_addr: INADDR_ANY.bigEndian)

            let result = withUnsafePointer(to: &address) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { pointer in
                    Darwin.bind(fd, pointer, socklen_t(MemoryLayout<sockaddr_in>.stride))
                }
            }
            close(fd)

            if result == 0 {
                return port
            }
        }

        throw NSError(domain: "LanDrop", code: 2, userInfo: [
            NSLocalizedDescriptionKey: "4318-4399 之间没有可用端口。"
        ])
    }

    private func showFatalError(message: String) {
        log("Alert: \(message)")
        let alert = NSAlert()
        alert.messageText = "LAN Drop"
        alert.informativeText = message
        alert.alertStyle = .critical
        alert.runModal()
        NSApp.terminate(nil)
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if navigationAction.navigationType == .linkActivated, let url = navigationAction.request.url, !url.absoluteString.hasPrefix("http://127.0.0.1:\(selectedPort)") {
            NSWorkspace.shared.open(url)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    private func log(_ message: String) {
        let line = "[\(ISO8601DateFormatter().string(from: Date()))] \(message)\n"
        if let data = line.data(using: .utf8) {
            if FileManager.default.fileExists(atPath: logFile) {
                if let handle = FileHandle(forWritingAtPath: logFile) {
                    _ = try? handle.seekToEnd()
                    try? handle.write(contentsOf: data)
                    try? handle.close()
                }
            } else {
                FileManager.default.createFile(atPath: logFile, contents: data)
            }
        }
    }
}

@main
final class LanDropApplication: NSObject {
    static func main() {
        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.setActivationPolicy(.regular)
        app.delegate = delegate
        app.run()
    }
}
