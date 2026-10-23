# T01: プロジェクト基盤 + 精密触覚エンジン

## 目的
このアプリでは、AI が触覚でユーザーに「話しかける」ことが体験の中心になる。T01 では、**端末のリニアモーター（LRA）を、その端末で使える中で最も精密な API で鳴らすエンジン**を作り、実機で手触りを評価できるベンチ画面を用意する。アバターや AI は扱わない。

## 前提
- `AGENTS.md`、`docs/product.md`（触覚フレーズ表）、`docs/architecture.md`（触覚エンジンの節）を読むこと

## 作業内容

### 1. Unity プロジェクトの作成
- `app/` に Unity 6 のプロジェクトを batchmode で作成する（`-createProject`）。テンプレートは最小構成にする
- 設定: Android を対象、IL2CPP、ARM64、Minimum API 26、Target API はインストール済みの最新、縦画面固定、Input System（new）のみ、Company は `vtubephone`、Product は `vtubephone`、パッケージ名は `com.vtubephone.app`
- `Packages/manifest.json`: `com.unity.inputsystem` と `com.unity.test-framework` を入れる。不要な既定パッケージ（Collab、Visual Scripting など）は外す
- asmdef: `VtubePhone.Core`（noEngineReferences: true）、`VtubePhone.Runtime`、`VtubePhone.Editor`、`VtubePhone.Tests.EditMode`
- `Editor/BuildScript.cs`: `BuildAndroid()` で `app/Builds/vtubephone.apk` を出力する（Development Build のオプションを環境変数で切り替えられるようにする）
- `Editor/SceneBuilder.cs`: `BuildAll()` で `Assets/VtubePhone/Scenes/HapticsBench.unity` を生成し、Build Settings に登録する

### 2. Core（純粋 C#）
- `HapticClip` / `HapticEvent`: transient と continuous を持つ。continuous の intensity は定数か、`[[ms, value], …]` のカーブで指定する。sharpness も同様にする
- `HapticClipParser`: JSON からクリップを読み込む。Core には JsonUtility がないので、小さな自前パーサを書くか、Runtime 側で DTO に変換してから Core に渡す。どちらでもよい
- `HapticClipFactory`: パラメータつきのクリップを生成する
  - `Knock(count 1..4, intervalMs = 170, intensity)`
  - `Bump(impact 0..1)`: impact から intensity と sharpness を決める（強い衝撃ほど強く、やや低く重くする）
- `HapticRenderer`: クリップを Tier ごとの「OS に渡す命令列」に変換する純粋関数
  - **Tier C（振幅の波形）**: `timings[]` と `amplitudes[1..255]` に量子化する。continuous のカーブは 5ms 刻みでサンプリングする。transient は「短いパルス（10〜20ms）」にし、sharpness が高いほど短く強くする
  - **Tier D（ON/OFF）**: intensity をデューティ比（PWM 風）で近似する
  - **Tier B（Composition）**: transient → プリミティブ（sharpness が高い順に TICK、CLICK、LOW_TICK、THUD を対応させる。intensity は scale にする）。continuous → SPIN、QUICK_RISE、SLOW_RISE などで近似するか、プリミティブに置き換えられない場合は Tier C の波形に落とす。どちらを選んだかを命令列に記録する
  - **Tier A（Envelope）**: continuous → BasicEnvelopeBuilder の制御点（intensity、sharpness、duration）。制約を守ること。開始と終了は intensity 0、制御点の間隔の最小と最大、点数の上限は能力レポートの値を使う。transient → Tier B と同様にプリミティブか Preset にする
  - 1 つのクリップに transient と continuous が混在するときに、Tier ごとにどう 1 回の OS 呼び出しにまとめるかを設計する。まとめられない場合は、近似の方針を決めてテストで固定する
- `HapticsRateLimiter`: 1 分あたりの上限、クールダウン、全体の強さ係数、全 OFF
- 12 個のクリップ定義を `Resources/HapticClips/*.json` に置く（`docs/product.md` の表のとおり）。**`yes` と `no` は触覚だけで区別できるように**、テンポ、長さ、鋭さを大きく変える

### 3. Android ネイティブ（`Assets/Plugins/Android/HapticEngine.java`）
- `getCapabilities()`: JSON 文字列を返す。含める項目は sdkInt、hasVibrator、hasAmplitudeControl、各プリミティブの supported と duration（API 31 以降）、envelope の supported と制約（API 36 以降。maxSize、min と max の制御点間隔、maxDuration）、resonant frequency と Q factor（取れる場合）、端末のメーカーとモデル
- `prepare(String key, String commandJson)`: 命令列から VibrationEffect を構築して**キャッシュ**する
- `play(String key)`: キャッシュ済みのエフェクトを `vibrate(effect, attrs)` で再生する。呼び出しから vibrate() を返すまでの ns を計測して保持する
- `playOnce(String commandJson)`: キャッシュせずに構築して再生する（`bump` のように強さが毎回変わるもの用）
- `cancel()`
- API 36 以降のクラスは、compileSdk の都合で**リフレクション経由**で呼んでもよい。その場合は失敗時に下の Tier へフォールバックすること
- `AndroidManifest`（`Plugins/Android` の custom manifest か、追加のマニフェスト）に `android.permission.VIBRATE` を入れる
- **公式リファレンスで API 名を確認してから書くこと**: `VibrationEffect.Composition`、`VibrationEffect.BasicEnvelopeBuilder`、`Vibrator.areEnvelopeEffectsSupported`、`VibratorEnvelopeEffectInfo`、`Vibrator.arePrimitivesSupported`、`getPrimitiveDurations`、`VibrationAttributes`

### 4. Runtime（C#）
- `IHapticsBackend`: `AndroidHapticsBackend`（JNI。`AndroidJavaObject` を初期化時に 1 回だけ取得して保持する）と `EditorHapticsBackend`（ログを出すだけ）を実装する
- `HapticsService`: 起動時に capabilities を取得 → Tier を決定 → 全クリップを `HapticRenderer` で変換して `prepare` → `Play(HapticClipId)`、`PlayKnock(count)`、`PlayBump(impact)` を提供する。すべての呼び出しは RateLimiter を通す
- **Tier を手動で上書きする機能**（ベンチ画面から A、B、C、D を強制する。ただし端末が対応していない Tier は選べない）を用意し、同じクリップを Tier 違いで比較できるようにする

### 5. HapticsBench シーン（SceneBuilder で生成）
- 上部: 能力レポート（Tier、各 Capability）とスクロールできるログ
- クリップごとのボタン（12 個）、knock の count を変えるスライダー、bump の impact を変えるスライダー
- Tier の上書き（A/B/C/D）、全体の強さスライダー、全 OFF トグル
- 各再生の「C# での呼び出しから vibrate() までの遅延（μs）」を表示する
- **ブラインドテストモード**: `yes` と `no` のどちらかをランダムに鳴らし、ユーザーが答えると正答率を表示する
- UI は uGUI でコードから生成してよい。見た目は最低限でよいが、ボタンは指で押しやすい大きさにする

## 受け入れ基準
1. EditMode テストがすべて通る。最低限、次をテストする
   - 各 Tier のレンダリング結果（timings の合計がクリップの長さと一致すること、振幅が 1〜255 の範囲に収まること、エンベロープが 0 で始まり 0 で終わること、制御点の制約を守ること）
   - `Knock(n)` が n 個の transient を正しい間隔で生成すること
   - `Bump(impact)` が impact について単調であること
   - RateLimiter（上限、OFF、強さ係数）
   - 12 個のクリップ JSON がすべて読み込めること
2. `SceneBuilder.BuildAll` が batchmode で成功し、シーンが Build Settings に登録されている
3. Android Build Support があれば `BuildAndroid` で APK が出力される（なければ ⏸ として報告する）
4. `HapticEngine.java` の Tier A と B の呼び出しが、公式 API 名と一致している（参照した URL を報告に書く）

## 範囲外
アバター、センサー、AI、サーバー、iOS
