# アーキテクチャ（Kotlin ネイティブ + WebView）

## 方針
- **起動を簡単にする**: Android Studio で `android/` を開いて ▶Run を押すだけ。npm のビルドも外部サーバーも不要（AI 機能を使うときだけサーバーを立てる）
- **振動とセンサーは Kotlin から Android の API を直接呼ぶ**（精度優先）
- **キャラクター表示だけを WebView に任せる**（three.js + pixiv 公式の three-vrm。Live2D は後回し）

## 構成
```
android/
  settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew
  core/        純粋 Kotlin/JVM モジュール（Android に依存しない）→ `gradlew :core:test` でテストする
    haptics/   HapticClip, HapticClipParser, HapticClipFactory, HapticRenderer(Tier A〜D), RateLimiter
    gesture/   MotionSample, GestureRecognizer, 各検出器, KnockSequencer, RhythmMatcher, Arbiter, GestureConfig
  app/         Android アプリ（minSdk 26 / targetSdk 36 / compileSdk 37）
    haptics/   HapticEngine（Vibrator / VibrationEffect を直接呼ぶ。API 36 のエンベロープも SDK_INT 判定で直接呼ぶ）
    sensing/   MotionSensor（SensorManager, SENSOR_DELAY_FASTEST）→ core の GestureRecognizer
    bridge/    AvatarBridge（WebView ⇔ Kotlin の双方向メッセージ）
    ui/        MainActivity（全画面 WebView + 字幕 + ヒント）、HapticsBenchActivity、GestureLabActivity（Compose）
    src/main/assets/web/
      index.html, main.js, avatar/*.js  … ES modules、ビルド工程なし
      vendor/three/, vendor/three-vrm/  … バージョンを固定してリポジトリに同梱（CDN に依存しない）
    src/main/assets/haptics/*.json      … 触覚クリップ定義（18 個）
server/        （T05）Node/TS: OpenAI と VOICEVOX（任意）
legacy-unity/  旧 Unity 実装。ロジック移植の参照用で、移植が終わったら削除する
```

## データの流れ
```
SensorManager ─▶ MotionSensor ─▶ GestureRecognizer(core) ─▶ GestureEvent ─┬▶ Brain(T04)
タッチ(WebView 上) ─▶ JS ─▶ bridge ─▶ TouchGestureRecognizer(core) ───────┘
重力ベクトル(60Hz) ─▶ bridge ─▶ JS アバター物理（箱の中を滑る）── 衝突 ─▶ bridge ─▶ HapticEngine.bump(impact)
Brain ─▶ HapticEngine.play(clip) / bridge ─▶ JS（表情・動作・字幕）
```
- WebView 側は描画と物理に専念し、判定ロジックは core（Kotlin）に置く
- ブリッジは `androidx.webkit` の `WebViewCompat.addWebMessageListener`（オリジンを `https://appassets.androidplatform.net` に限定）を使う。アセットは `WebViewAssetLoader` で配信する
- VRM はアプリ内のファイル選択（SAF）でスマホ内から読み込み、アプリ領域にコピーして WebView に渡す。**再ビルドせずにモデルを差し替えられる**ようにする

## 触覚エンジン（精度最優先。T01 の設計を引き継ぐ）

### クリップ形式（端末に依存しない。AHAP 互換を意識）
```json
{ "id": "knock", "events": [
  { "t": 0,   "type": "transient",  "intensity": 0.9, "sharpness": 0.25 },
  { "t": 400, "type": "continuous", "duration": 300, "intensity": [[0,0.0],[80,0.6],[300,0.0]], "sharpness": 0.2 } ] }
```

`primitive` イベントで素材を直接指定できる。`scale` は0〜1。`transient` / `continuous` は従来どおり使用可能。
```json
{ "id": "dizzy", "strictTiming": false, "events": [
  { "t": 0, "type": "primitive", "name": "spin", "scale": 0.9 },
  { "t": 180, "type": "primitive", "name": "spin", "scale": 0.6 } ] }
```
Tier Bでは端末のプリミティブの長さを使い、重なりを必要最小限だけ遅らせ、各移動量を命令の `reason` に記録する。`strictTiming: true` は重なったらC/Dへフォールバックする。`knock` は時刻厳守。API 30では長さを取得できないため、安全にC/Dへフォールバックする。詳細と近似表は [触覚語彙の設計](haptic-vocabulary.md) を参照。

### Tier（起動時に端末の能力を判定し、使える中で最も精密なものを選ぶ）
| Tier | 条件 | API |
|---|---|---|
| A | API 36 以上かつ `areEnvelopeEffectsSupported()` | `VibrationEffect.BasicEnvelopeBuilder`（continuous だけのクリップ） |
| B | API 31 以上で、使うプリミティブが対応し長さを取得できる | `VibrationEffect.startComposition().addPrimitive(id, scale, delay)`（transient / primitive のみのクリップ） |
| C | `hasAmplitudeControl()` | `createWaveform(timings, amplitudes, -1)` |
| D | 上のどれにも該当しない | ON/OFF の `createWaveform`（PWM 風） |

### 精度要件
1. クリップは**1 回の OS 呼び出しで丸ごと再生する**。タイマーで分割して鳴らさない
2. VibrationEffect は**事前に構築してキャッシュ**する。呼び出しから `vibrate()` が返るまでの時間を計測して表示する
3. `bump` は衝突の強さに比例させる。JS → ネイティブの遅延も計測する
4. 能力レポート（API レベル、Tier、プリミティブの対応状況と長さ、エンベロープの制約、amplitude control、共振周波数、Q 値）
5. 安全策: 全体の強さ、全 OFF、1 分あたりの上限、自発的な振動のクールダウン、バックグラウンド時の停止
6. **`VibrationAttributes.USAGE_MEDIA`**（API 33 未満では AudioAttributes の `USAGE_GAME`）を使う。`USAGE_TOUCH` は OS のタッチ振動設定で消えてしまうので使わない
7. Composition にエンベロープや波形を混ぜることはできない。そのため Tier B で鳴らしたいクリップは transient / primitive だけで設計する

## センサー（入力精度）
- `SensorManager` で `TYPE_ACCELEROMETER`、`GYROSCOPE`、`GRAVITY`、`LINEAR_ACCELERATION`、`GAME_ROTATION_VECTOR` を `SENSOR_DELAY_FASTEST` で購読する。`SensorEvent.timestamp`（ns）を使う
- タップのリズムには、フレーム時刻ではなく `MotionEvent.getEventTime()` 系のタイムスタンプを使う
- 判定ロジックはすべて core に置き、合成データと実機で記録した CSV の再生でテストする
