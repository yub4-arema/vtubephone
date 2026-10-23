# T01: Android 基盤 + 精密触覚エンジン（Unity 版からの移植）

## 目的
Android Studio で ▶Run を押すだけで起動するプロジェクトを作り、旧 Unity 版の触覚エンジンを Kotlin に移植する。実機で手触りを評価できるベンチ画面と、T03 で使う WebView の土台を用意する。

## 移植元（読むだけで、編集しない）
- `legacy-unity/Assets/VtubePhone/Core/HapticClip.cs`、`HapticClipParser.cs`、`HapticRenderer.cs`、`HapticsRateLimiter.cs`、`HapticsBlindTest.cs`
- `legacy-unity/Assets/Plugins/Android/HapticEngine.java`（Android 側の実装。リフレクションをやめて直接呼ぶ形にする）
- `legacy-unity/Assets/VtubePhone/Resources/HapticClips/*.json`（12 個のクリップ。そのまま使う）
- `legacy-unity/Assets/VtubePhone/Tests/EditMode/HapticsTests.cs`（27 件のテスト。JUnit に移植する）
- `legacy-unity/haptics-engine.md`（Tier ごとの対応表と、設計判断の記録）

## 作業内容

### 1. Gradle プロジェクト `android/`
- モジュールは `:core`（Kotlin/JVM。Android に依存しない）と `:app`（Android アプリ）
- AGP、Kotlin、Gradle は安定版の最新に近いものにし、wrapper を同梱する。システムの JDK は 25 なので、Gradle が JDK 25 で動くこと、または toolchain で回避できることを確認する
- `:app`: applicationId `com.vtubephone.app`、minSdk 26、compileSdk と targetSdk は 36、縦画面、Jetpack Compose（デバッグ画面用）、`androidx.webkit`
- **Android SDK がない環境でも `./gradlew :core:test` が動くこと**（SDK がないときは `:app` を設定から外す、などの工夫をする）
- `.gitignore` に Android と Gradle の生成物を追加する

### 2. `:core` の haptics（C# からの移植）
- HapticClip、パーサ（JSON は kotlinx.serialization か org.json 相当。JVM テストで動くもの）、ClipFactory（Knock、Bump）、Renderer（Tier A〜D）、RateLimiter、BlindTest
- **振る舞いは C# 版と同じにする**。27 件のテストを JUnit に移植し、全件通すこと
- クリップの JSON は `android/app/src/main/assets/haptics/` に置き、`:core` のテストからも読めるようにする（テストのリソースに指定するなど）

### 3. `:app` の HapticEngine（Kotlin）
- 旧 `HapticEngine.java` と同じ機能（能力レポート、prepare とキャッシュ、play、playOnce、cancel、計測）を、**リフレクションなしで直接呼ぶ**形で実装する（compileSdk 36 なので、`Build.VERSION.SDK_INT` で分岐すればよい）
- VibrationAttributes は `USAGE_MEDIA`、API 33 未満では AudioAttributes の `USAGE_GAME` を使う。OS のタッチ振動設定は見ない
- `HapticsService`: 起動時に能力を判定し、Tier を決め、全クリップと knock 1〜4 を prepare する。`play(id)`、`playKnock(n)`、`playBump(impact)`、Tier の上書き、強さ、OFF を提供する。onPause で cancel する

### 4. 画面
- **MainActivity**: 全画面の WebView。`WebViewAssetLoader` で `assets/web/index.html` を表示する（T01 では「vtubephone」と表示し、ボタン 1 つで `call` を鳴らすだけ）
  - ブリッジ: `WebViewCompat.addWebMessageListener`（オリジンは `https://appassets.androidplatform.net` に限定）。JS から `{type:"haptic", id:"call"}` を送ると振動する
  - JS からネイティブへの遅延を計測し、ログに出す
  - 右上の「🔧」から HapticsBench へ移動できる
- **HapticsBenchActivity**（Compose）: 旧 `HapticsBench.cs` と同じ機能
  - 能力レポートとログ
  - 12 個のクリップのボタン
  - knock の回数と bump の強さのスライダー
  - Tier の上書き、強さ、全 OFF
  - 遅延の表示（μs）
  - **はい／いいえのブラインドテスト**

## 受け入れ基準
1. `cd android && ./gradlew :core:test` がすべて通る（移植した 27 件以上）
2. Android SDK がある場合は `./gradlew :app:assembleDebug` が成功する。ない場合は ⏸ と報告する
3. HapticEngine の Android API 呼び出しが公式リファレンスと一致している（参照 URL を報告に書く）
4. `docs/dev-setup.md` の手順（Android Studio で開いて ▶Run）で起動する構成になっている（run configuration を含める）

## 範囲外
センサー（T02）、VRM の表示（T03）、AI
