# LAN Drop

LAN Drop 是一个面向 **Android 手机 ↔ Mac** 的局域网 Wi-Fi 文件快传工具。它不需要云服务，不需要登录账号，只要手机和 Mac 在同一个 Wi-Fi 下，就可以在两端之间传照片、视频和文件。

![LAN Drop icon](assets/icon/lan-drop-icon-preview.png)

## Features

- Android 手机自动发现同一局域网内的 Mac 接收端。
- Android 支持从系统相册、文件管理器通过“分享”直接发送到 LAN Drop。
- Android 后台上传，支持通知栏进度、App 内进度、已用时间、预计剩余时间和当前速度。
- Android 使用约 8MB 分片上传，支持断点续传；如果连接到旧版 Mac 服务，会自动降级到兼容上传接口。
- Mac 端提供网页接收界面、二维码入口、最近文件列表和“一键打开保存文件夹”。
- Mac 可以选择本机文件放进“发到手机”列表，手机打开同一页面即可下载。
- Android App 提供“打开 Mac 发来的文件”入口，发现 Mac 后可直接进入下载页面。
- Mac 桌面 App 自动启动本地 Node 服务，桌面版默认免访问码。
- 命令行模式默认生成访问码，也可通过环境变量关闭。
- 所有文件默认保存在 `~/Downloads/LANDrop`，并按日期分目录存放。
- 无外部服务器，文件只在局域网内传输。

## Project Structure

```text
.
├── server.js                 # Node/Express receiver and API server
├── public/                   # Mac/web upload UI
├── macos/                    # Native macOS Swift WebView wrapper
├── android/                  # Native Android Kotlin uploader
├── scripts/generate_icons.py # Icon generator for Android and macOS
├── assets/icon/              # Icon previews and source PNG
└── build_macos_app.sh        # macOS app build script
```

## Requirements

- macOS 13 or newer for the desktop wrapper.
- Node.js 18 or newer.
- Android Studio or Android SDK/Gradle for building the Android APK.
- Python 3 with Pillow if you want to regenerate icons.

## Quick Start: Mac Receiver

```bash
npm install
npm start
```

The terminal prints:

- Mac local page URL
- Phone LAN URL
- Upload folder
- Access code

Open the printed LAN URL from an Android phone browser, or install the Android app for auto-discovery, background upload, and opening Mac-to-phone downloads.

## Build The macOS App

```bash
npm install
npm run desktop:build-mac
open "dist/LAN Drop.app"
```

The macOS app:

- finds an available port in `4318...4399`
- starts `server.js`
- opens the LAN Drop UI
- writes logs to `/tmp/lan-drop-mac-app.log`
- stores the build-time project path in the app bundle so the copied app can still find the local server source

## Build The Android App

```bash
cd android
./gradlew assembleDebug
```

Debug APK:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Install the APK on an Android phone, open the Mac app, then tap `自动发现 Mac` in Android. You can also select photos/videos/files in another app, tap `分享`, and choose `LAN Drop`.

For Mac → Android transfer, choose files in the Mac web UI under `Mac 发到手机`, then open the same LAN Drop page from the phone and tap `下载到手机`. The Android app also has `打开 Mac 发来的文件` to jump into that page.

## Configuration

The server reads these optional environment variables:

```bash
PORT=4318
HOST=0.0.0.0
LAN_DROP_DIR=/absolute/upload/path
LAN_DROP_CODE=123456
LAN_DROP_MAX_LABEL="No hard cap"
```

Disable access code:

```bash
LAN_DROP_CODE=off npm start
```

The macOS desktop wrapper sets `LAN_DROP_CODE=off` by default for a simpler trusted-home-LAN flow. If you want an access code, run the server from terminal with `LAN_DROP_CODE=123456 npm start`.

## API

Compatibility endpoints:

- `GET /api/status`
- `POST /api/upload`
- `POST /upload`
- `GET /api/files`
- `GET /files`
- `GET /api/history`
- `GET /history`
- `GET /download/:filename`
- `POST /api/open-upload-folder`
- `GET /api/phone-files`
- `POST /api/phone-files`
- `DELETE /api/phone-files/:filename`
- `GET /phone-download/:filename`

Android resumable upload endpoints:

- `POST /api/upload-session`
- `POST /api/upload-chunk`
- `POST /api/upload-complete`

UDP discovery:

- Port: `50000`
- Request: `{"type":"discover","device_name":"Android"}`
- Response: `{"type":"response","device_name":"Mac","ip":"...","port":4318,"baseUrl":"http://...","authRequired":false}`

## Regenerate Icons

```bash
python3 scripts/generate_icons.py
```

This creates:

- Android launcher PNGs in `android/app/src/main/res/mipmap-*`
- macOS `macos/AppIcon.icns`
- preview PNGs in `assets/icon`

## Privacy And Security

- LAN Drop does not upload files to cloud services.
- Files are transferred over your local Wi-Fi network using HTTP.
- Use access codes in untrusted networks.
- Do not expose the receiver port directly to the public internet.
- Uploaded files, local build artifacts, SDK paths, and Gradle caches are excluded from Git by `.gitignore`.

## Roadmap

- Better transfer queue management on Android.
- Native Android in-app downloader for Mac-to-phone files.
- Optional HTTPS for stricter network environments.
- Native macOS settings for upload directory and access code.
- Android release signing and GitHub Releases artifacts.

## License

MIT
