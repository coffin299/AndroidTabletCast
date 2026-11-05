# Android Tablet Cast

Android タブレットの画面を、同じ Wi-Fi の Windows 11 へ映す。マウスとキーボードはタブレットへ返す。インターネット上の中継は使わない。

画面の説明は [docs/index.html](docs/index.html)。手順は [docs/usage.md](docs/usage.md)。構成は [docs/architecture.md](docs/architecture.md)。バイト列は [docs/protocol.md](docs/protocol.md)。

## リリースから使う

GitHub の Actions で **Release** を手動実行し、タグ（例: `v0.1.0`）を入力する。形式は `v1.2.3` または `v1.2.3-rc.1`。既存のタグは上書きしない。

できるもの:

- `AndroidTabletCast-android.apk`
- `AndroidTabletCast-win-x64.zip`

タブレットに APK を入れ、Windows では zip を展開して `AndroidTabletCast.exe` を起動する。初回は PIN、信頼を登録してタブレット側のスイッチをオンにすると、その PC は次回から PIN なしでつながる。一覧の同じ端末は 1 件だけ出る。OBS で取り込むときは、Windows の「OBS 用ウィンドウ」をウィンドウキャプチャする。

## 開発ビルド

Android（`ANDROID_HOME` に SDK があること）:

```bat
cd android
gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Windows（.NET 8 SDK）:

```powershell
dotnet test windows\AndroidTabletCast.Protocol.Tests\AndroidTabletCast.Protocol.Tests.csproj
powershell -ExecutionPolicy Bypass -File windows\tools\fetch-ffmpeg.ps1
dotnet build windows\AndroidTabletCast\AndroidTabletCast.csproj
```

`fetch-ffmpeg.ps1` は FFmpeg 7.1.5 の LGPL 共有 DLL を `windows/ffmpeg/bin` に置く。ビルド時に exe の横へコピーする。ライセンスは [windows/FFMPEG-LGPL.txt](windows/FFMPEG-LGPL.txt)。

## 署名 Secrets

上書きインストールできる APK にするには、リポジトリの Actions secrets に次を入れる。

- `ANDROID_KEYSTORE_BASE64` … keystore を Base64 にした文字列
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

未設定の Release は debug 署名になる。その APK へ更新するときは、一度アンインストールする。

## ライセンス

アプリ本体は [LICENSE](LICENSE) の MIT。FFmpeg の DLL は LGPL 2.1。
