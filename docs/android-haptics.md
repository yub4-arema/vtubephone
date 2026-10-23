# Android 触覚エンジン（T01）

旧 Unity の触覚ロジックと27テストケースを Kotlin/JVM に移植した。12個の JSON は内容を変更せず `android/app/src/main/assets/haptics/` にコピーし、core のテストリソースからも同じファイルを読む。Unity 側は変更しない。

## 再生の経路

`HapticsService` が能力を取得し、12クリップと knock 1〜4 を prepare する。全体強度・Tier を変えたときだけキャッシュを再構築する。連続値の bump は旧実装と同じく都度 render / playOnce し、構築を含む時間と native 呼び出し時間を分けて表示する。impact は0〜1に制限し、強くなるほど強度を増やし鋭さを下げる。

| 要求 Tier | 使用条件と実際の再生 |
|---|---|
| A | API 36、envelope 対応、continuous のみ、制御点数・間隔・総長が端末制約内なら BasicEnvelopeBuilder。transient のみなら B を試す |
| B | transient のみ、使用する全 primitive が対応、API 31 以降で長さを取得済み、重なりなしなら Composition |
| C | amplitude control があれば振幅付き波形。なければ D |
| D | 1ms のパルス密度による ON/OFF 波形 |

A/B が条件を満たさない場合、クリップ全体を C/D の単一波形にする。continuous は5msでサンプルし transient の境界は1msで保持する。B の delay は「前の primitive の終了から」の間隔なので、デバイスから取得した長さを開始時刻から引く。API 30 では長さが不明なため B を要求しても波形になる。OS が effect の構築を拒否した場合も、事前構築時に C/D へフォールバックする。

1クリップにつき `vibrate()` は1回だけ。タイマーによる触覚の分割再生やリフレクションは使わない。メディア属性を使用し、OS のタッチ振動設定は参照しない。OFF、強度0、毎分60回、自発再生の2秒クールダウンを適用し、Activity の onPause で cancel して再生要求も止める。

## 計測とブラインドテスト

ベンチの呼出時間は service の開始から OS 呼び出しが返るまで、native 時間はキャッシュ参照から `vibrate()` が返るまでを `System.nanoTime()` で測定する。OS への受付成功を意味し、モーターの始動やユーザーに届いたことは保証しない。拒否・無音時は成功として採点しない。OS 設定による抑制は API の戻り値から検出できないため、テスト前に手で振動を確認する。

Web 側はビルド不要の ES modules。`WebViewAssetLoader` と、`https://appassets.androidplatform.net` の main frame に限定した `addWebMessageListener` を使う。外部へのロードは遮断する。`{type:"haptic",id:"call"}` が基本メッセージで、計測用フィールドは任意。JS `performance.now()` と native の単調時計を5回の往復で同期し、最小 RTT のサンプルから時計差を推定する。片道遅延はログに ±RTT/2 の不確実性を添えて出す。通信の非対称性と WebView の時計精度の影響があり、3ms目標は実機で評価する。

ブラインド中は名前・Tier・波形の詳細を隠し、再生受付が成功したサンプルに1回だけ回答できる。OFF、画面離脱、テストモード切替で未回答試行を取り消す。全体強度と Tier はブラインド中に固定する。

## 確認した公式 API

- [Vibrator](https://developer.android.com/reference/android/os/Vibrator): `hasAmplitudeControl()` (26)、`arePrimitivesSupported(int...)` (30)、`getPrimitiveDurations(int...)` (31)、`getResonantFrequency()` / `getQFactor()` (34)、`areEnvelopeEffectsSupported()` / `getEnvelopeEffectInfo()` (36)。取得できない周波数・Q値は -1 と表示
- [BasicEnvelopeBuilder](https://developer.android.com/reference/android/os/VibrationEffect.BasicEnvelopeBuilder): API 36、`setInitialSharpness(float)`、`addControlPoint(float,float,long)`、`build()`。開始強度は暗黙の0、終端も0
- [VibratorEnvelopeEffectInfo](https://developer.android.com/reference/android/os/vibrator/VibratorEnvelopeEffectInfo): `getMaxSize()` は int、制御点間隔と総長の getter は long
- [Composition](https://developer.android.com/reference/android/os/VibrationEffect.Composition): API 30 の `addPrimitive(int,float,int)` の delay は前の primitive 終了からの休止時間
- [VibrationEffect](https://developer.android.com/reference/android/os/VibrationEffect): `createWaveform(long[],int[],int)` / `createWaveform(long[],int)`。repeat は -1
- [VibrationAttributes](https://developer.android.com/reference/android/os/VibrationAttributes#USAGE_MEDIA): API 33 以降の `USAGE_MEDIA` と `Vibrator.vibrate(VibrationEffect,VibrationAttributes)`
- [AudioAttributes](https://developer.android.com/reference/android/media/AudioAttributes#USAGE_GAME): API 26〜32 では `USAGE_GAME` / `CONTENT_TYPE_SONIFICATION`
- [WebViewCompat](https://developer.android.com/reference/androidx/webkit/WebViewCompat): `addWebMessageListener`。`WEB_MESSAGE_LISTENER` の対応確認後だけ登録する
- [WebViewAssetLoader](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader): 同梱アセットを HTTPS の専用オリジンで配信する

## ビルド構成の根拠

- [AGP 9.2 の互換表](https://developer.android.com/build/releases/agp-9-2-0-release-notes): Gradle 9.4.1、JDK 17以上、Build-Tools 36.0.0
- [Gradle の Java 互換表](https://docs.gradle.org/current/userguide/compatibility.html): JDK 25 は Gradle 9.1 以降で実行可能
- [Kotlin の互換表](https://kotlinlang.org/docs/gradle-configure-project.html): Kotlin 2.4.21 と Gradle 9.4.1 / AGP 9.2.1 の組合せはサポート範囲内
- Compose BOM は 2026.02.01 を採用。Google Maven の AAR メタデータで ui-android 1.10.4 は minCompileSdk 35 と確認した。2026.09.00 の ui-android 1.12.1 は37を要求するため使用しない

## 実機での評価

API 26（振幅あり/なし）、31（primitive 対応）、36（envelope 対応）の実機で、全クリップとフォールバック、knock の回数、bump の強弱、はい/いいえの識別性、メディア振動設定、OFFとバックグラウンド停止、遅延を確認する。エミュレーターや JVM テストだけでは手触り・物理遅延・OS の抑制は検証できない。

## 今回の検証（2026-10-23）

- JDK 25.0.2 / Gradle 9.4.1 で `:core:test` と `:core:test -PcoreOnly=true` が成功。移植27件と追加2件、合計29件、失敗0件
- 12 JSON の旧版とのバイト一致、Manifest・共有 Run 構成の XML、Web ES module の構文を確認
- JS の疑似通信で5回の時計同期、時計差・不確実性を含む再生要求、再生拒否とブリッジ未対応時の表示を確認
- 既存の Google 配布 API 36 参照 JAR と Google Maven の AndroidX AAR を使用し、core と app の全 Kotlin ソース（Compose 画面を含む）を一時的な JVM コンパイル環境で静的コンパイルした。これは APK のビルド、リソース処理、実行時互換性の検証とは別
- 一時 init script で `:app` を明示的に include した `help` が成功し、SDK のない環境でも app の Gradle DSL・プラグイン構成を検証した。確認中に見つかった KGP と AGP のクラスローダー衝突は、ルートで両プラグインを `apply false` 宣言して解消した
- Android SDK と接続実機がないため `:app:assembleDebug` / install / Android Studio の実際の起動操作は未実施
