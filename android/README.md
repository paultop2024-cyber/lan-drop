# LAN Drop Android

Native Android client for LAN Drop.

## What It Does

- Discovers the Mac receiver automatically on the same Wi-Fi.
- Saves the receiver URL locally.
- Uploads files in the background with WorkManager.
- Shows upload percentage, elapsed time, estimated remaining time, and current speed.
- Uploads in about 8MB chunks with resumable transfer support.
- Falls back to `/api/upload` if the Mac receiver does not support resumable endpoints.
- Accepts files from Android's system share sheet, including photos, videos, and documents.
- Shows Mac-to-phone files inside the Android app.
- Receives Mac-to-phone files directly into `Downloads/LANDrop` through Android Download Manager.

## Build

```bash
./gradlew assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Use

1. Start `LAN Drop.app` on the Mac.
2. Keep Android and Mac on the same Wi-Fi.
3. Open the Android app and tap `自动发现 Mac`.
4. Tap `打开相册/文件` or share files from another Android app to `LAN Drop`.
5. Tap `直接发送到 Mac`.
6. To receive files from the Mac, tap `查看 Mac 发来的文件`, then tap `接收到手机`.

On Android 13+, allow notification permission if you want to see background upload progress in the notification area.
