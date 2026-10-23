# T01 触覚エンジン

## 起動・ビルド

`app/` は Unity 6000.0.44f1 用の最小構成。Unity のライセンスを有効化した後、リポジトリルートで次を実行する。

```powershell
$unityExe = 'C:/Program Files/Unity/Hub/Editor/6000.0.44f1/Editor/Unity.exe'
& $unityExe -batchmode -nographics -quit -projectPath app -executeMethod VtubePhone.Editor.SceneBuilder.BuildAll -logFile artifacts/scenes.log
& $unityExe -batchmode -nographics -projectPath app -runTests -testPlatform EditMode -testResults artifacts/editmode.xml -logFile artifacts/editmode.log
$env:VTUBEPHONE_DEVELOPMENT_BUILD = '1' # 0 または未設定なら通常ビルド
& $unityExe -batchmode -nographics -quit -projectPath app -executeMethod VtubePhone.Editor.BuildScript.BuildAndroid -logFile artifacts/build.log
```

GUI を使う場合も最初に `SceneBuilder.BuildAll` を実行する。Android の設定、Input System のみを使う設定、シーン生成と Build Settings 登録はこのメソッドが行う。Android ターゲットへの切り替えは `BuildAndroid` が行う。Target API は Unity の Automatic（インストール済みの最新）、Minimum API は 26。シーンの YAML は編集しない。UI は生成したシーンの `HapticsBench.Start` が uGUI と InputSystemUIInputModule で組み立てる。

Unity を起動できない環境での Core 検証:

```powershell
dotnet test tools/CoreTests/CoreTests.csproj --logger 'trx;LogFileName=core-tests.trx' --results-directory artifacts
```

同じ `HapticsTests.cs` を NUnit で実行する。これは Unity EditMode、JNI、画面表示の検証を代替しない。

`./tools/verify-static.ps1` はこのテストに加え、インストール済み Unity の参照 DLL で Runtime / Editor もコンパイルする。`-AndroidJar <android.jar> -Javac <javac.exe>` を指定すると Java の静的コンパイルも行う。Unity のテンプレートキャッシュに UI / Input System の DLL がある環境用。

API 31 と API 36 の両方で Java を検証する場合（Google 配布の android.jar を配置済みの場合）:

```powershell
./tools/verify-static.ps1 -AndroidJar artifacts/android-sdk-reference/android-31.jar
./tools/verify-static.ps1 -AndroidJar artifacts/android-sdk-reference/android-36.jar
```

## レンダリングとフォールバック

| 選択 Tier | 命令 |
|---|---|
| A / continuous のみ | BasicEnvelopeBuilder。暗黙の開始 intensity 0、最後も 0。端末の点数、区間長、全長の制約を満たす均等間隔でサンプリング。満たせない場合は C/D |
| A / transient のみ | B のプリミティブに変換 |
| B / transient のみ | sharpness の降順に TICK(7)、CLICK(1)、LOW_TICK(8)、THUD(2)。すべての対応と長さを確認 |
| A/B / 混在、B / continuous | クリップ全体を C/D の単一波形へ変換。命令の reason に記録 |
| C | continuous を 5ms ごとにサンプリング。transient の開始・終了は 1ms 精度。無音の振幅は 0、発振時は 1..255。重なりは最大値で合成 |
| D | C のサンプルを 1ms のパルス密度による ON/OFF に変換。開始は必ず OFF 区間（長さ 0 も可） |

Composition の delay は前のプリミティブの**終了から**計算する。端末が返した duration を引いて、イベントの開始時刻を保つ。API 30 はプリミティブ長を取得できないため、Tier B を選択できても実際の命令は C/D に落とす。重なるプリミティブ、未対応のプリミティブでも全体を C/D に落とす。Composition の全長は端末固有の最後のプリミティブ長に依存し、波形の全長とは異なることがある。

自動選択時のクリップ別の実際の描画 Tier は次のとおり。A 端末は Envelope と必要なプリミティブをサポート、B 端末は API 31 以降で必要なプリミティブをサポート、どちらも振幅制御ありとする。プリミティブ長はテスト用の代表値（THUD 150ms、LOW_TICK 40ms、TICK / CLICK 20ms）であり、実測値ではない。A の Envelope 制約は全 continuous クリップを収容できる前提。

| クリップ | Tier A 端末 | Tier B 端末 | Tier C 端末 |
|---|---|---|---|
| knock（JSON / count 1〜4） | B | B | C |
| thud | B | B | C |
| bump（JSON / impact 0〜1） | B | B | C |
| yes | B | B | C |
| no | A | C | C |
| call | B | B | C |
| purr | A | C | C |
| heartbeat | B | B | C |
| nervous | B | B | C |
| giggle | B | B | C |
| dizzy | A | C | C |
| poke | B | B | C |

実端末では未対応・長さ不明・重なり・Envelope 制約違反で C、振幅制御もなければ D に落ちる。例えば heartbeat は THUD が 150ms を超えると重なるため C/D になる。表は `ResourceTiersMatchDocumentedDeviceMatrix` で全 12 JSON を検証する。

thud は 0ms に intensity 1 / sharpness 0.1 の THUD、220 / 320 / 420ms に intensity 0.5 / 0.3 / 0.15、sharpness 0.3 の LOW_TICK を置く。THUD 150ms でも次まで 70ms、LOW_TICK 40ms でも次まで 60ms の余裕を取る。THUD 60 / 100 / 150ms のモックで、thud・knock（JSON と count 1〜4）・yes・call が Tier B のまま開始時刻を保つことを検証する。

API 36 の Composition と BasicEnvelopeBuilder で混在を合成する方法には依存せず、全体を波形で近似する。新しい VibrationEffect.Builder は公式リファレンスでは version 37.2 の API のため使用しない。API 36 のクラスとメソッド、および VibrationAttributes の再生経路はリフレクションで呼ぶ。構築時の失敗は prepare 内で C/D にフォールバックし、その結果をベンチのログに表示する。

全クリップと knock(1..4) は起動時に prepare し、Tier・全体強度を変更すると再構築する。通常再生はキャッシュ検索と vibrate だけで、Unity のフレームによる分割、タイマー、連続した vibrate 呼び出しを行わない。bump は呼び出し内で生成・構築・単発再生する。

## 安全策・計測・ブラインドテスト

- 初期値は 60 再生/ローリング 60 秒、自発再生のみ 2 秒クールダウン。全 OFF・強度 0・バックグラウンド・振動器なしでは再生しない。失敗した再生要求も回数上限を消費する。
- pause または focus 喪失で cancel。API 33 以降は VibrationAttributes.USAGE_MEDIA、以前は AudioAttributes.USAGE_GAME。属性のリフレクションに失敗した場合も USAGE_GAME にフォールバックする。OS のタッチフィードバック設定は参照せず、アプリ内の全 OFF は RateLimiter.Enabled で行う。設定を迂回するフラグは使わない。
- 能力レポートの `usage` に実際の再生経路の属性名を記録し、ベンチ表示とログに出す。端末の media 振動の強さの設定値は今回取得せず、省略する。アプリの強度スライダーはクリップの intensity に掛ける係数であり、OS の設定値ではない。
- C# の単調時計で呼び出し開始から JNI の再生メソッドが戻るまでを μs で表示する。これは vibrate が戻るまでの上限であり、モーターの物理的な立ち上がり時間ではない。ネイティブの play 入口から vibrate 復帰までの時間も ns で表示する。bump の C# 値は構築時間を含む。3ms 目標の達成は実機で評価する。
- ブラインドモードはクリップ名とレンダリング詳細をログから隠す。ランダムな yes/no の再生成功後に 1 回だけ回答できる。再生拒否、OFF、バックグラウンド移行では採点しない。Editor はシミュレーションと明記し、触覚識別の評価には使用しない。
- yes は 110ms 間隔の鋭い 2 発、no は 380ms の鈍い持続。振幅制御なし端末での識別性、実際の強度、OS による抑制は実機で確認する。

## 確認した公式 API（2026-10-23）

- [VibrationEffect](https://developer.android.com/reference/android/os/VibrationEffect): startComposition、createWaveform(long[], int[], int) と createWaveform(long[], int)。
- [Composition](https://developer.android.com/reference/android/os/VibrationEffect.Composition): addPrimitive(int, float, int)、compose、各 PRIMITIVE 定数、delay の意味。
- [BasicEnvelopeBuilder](https://developer.android.com/reference/android/os/VibrationEffect.BasicEnvelopeBuilder): コンストラクタ、setInitialSharpness(float)、addControlPoint(float, float, long)、build()。開始強度は暗黙に 0、最後の制御点は 0 が必須。
- [Vibrator](https://developer.android.com/reference/android/os/Vibrator): arePrimitivesSupported(int...) は API 30、getPrimitiveDurations(int...) は API 31、areEnvelopeEffectsSupported()/getEnvelopeEffectInfo() は API 36。getResonantFrequency()/getQFactor() は API 34（取得不能時は -1 をレポート）。
- [VibratorEnvelopeEffectInfo](https://developer.android.com/reference/android/os/vibrator/VibratorEnvelopeEffectInfo): **android.os.vibrator パッケージ**。getMaxSize、getMinControlPointDurationMillis、getMaxControlPointDurationMillis、getMaxDurationMillis。
- [VibrationAttributes](https://developer.android.com/reference/android/os/VibrationAttributes#USAGE_MEDIA): USAGE_MEDIA は API 33 追加。ゲームなどのインタラクティブなメディア用。[Vibrator.vibrate](https://developer.android.com/reference/android/os/Vibrator#vibrate(android.os.VibrationEffect,%20android.os.VibrationAttributes)) の属性付きオーバーロードは API 33。
- [AudioAttributes](https://developer.android.com/reference/android/media/AudioAttributes#USAGE_GAME): 古い OS 用の USAGE_GAME と CONTENT_TYPE_SONIFICATION。
- [VibrationEffect.Builder](https://developer.android.com/reference/android/os/VibrationEffect.Builder): version 37.2 の API として確認。今回使用しない。

さらに Google 配布の platform-31_r01.zip / platform-36_r02.zip の android.jar に対する javac と、API 36 の javap でリフレクション対象のシグネチャを照合した。API 26 を支える旧式 API について deprecation 警告が出るが、コンパイルは成功する。

T01 rev1 の検証では `dotnet test tools/CoreTests/CoreTests.csproj` が 27 件すべて成功。上記 `verify-static.ps1` の API 31 / API 36 の両コマンドも成功し、Core / Runtime / Editor の C# 静的コンパイルは警告・エラーともに 0 件。Java は既存の互換経路に deprecation 警告あり。変更した USAGE_MEDIA、Builder.setUsage(int)、Vibrator.vibrate(VibrationEffect, VibrationAttributes) は API 36 の javap でも確認した。

## この環境で未完了の検証

2026-10-23、`-createProject`、EditMode、SceneBuilder の起動がすべて `No valid Unity Editor license found` で終了した。プロジェクトのソースと最小 manifest は配置済みだが、Unity が生成する ProjectSettings、シーン、Build Settings は未生成。Android Build Support も指定 Editor に存在しないため、APK は未生成。Unity の実コンパイル、シーン描画、実機再生を通過したとは扱わない。

T01 rev1 でも EditMode を再試行し、同じライセンスエラー（終了コード 1）を確認した（`artifacts/editmode-rev1.log`）。タスクの指示に従って以降の Unity コマンドはスキップ。Android Build Support が未導入であることも再確認した。
