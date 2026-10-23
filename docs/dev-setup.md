# 動かし方（デプロイ手順）

## 最初の 1 回だけ
1. **Android Studio をインストールする**（https://developer.android.com/studio）。初回起動時のセットアップウィザードは「Standard」を選び、すべて次へ進めれば SDK も入る
2. **スマホの準備**
   - 設定 → デバイス情報 → 「ビルド番号」を 7 回タップする（開発者モードになる）
   - 設定 → システム → 開発者向けオプション → 「USB デバッグ」を ON にする
   - USB で PC につなぎ、スマホに出る「USB デバッグを許可しますか？」で「許可」を押す

## 毎回の起動
1. Android Studio で **`android/` フォルダを開く**（初回は Gradle の同期に数分かかる）
2. Run 構成 **`app`** と自分のスマホを選び、**▶（Run）を押す**（共有構成は `android/.run/app.run.xml`）
3. スマホでアプリが起動する

Android Studio の Standard セットアップで入る **Android SDK Platform 37** を使う（compileSdk 37、targetSdk 36、minSdk 26 = Android 8.0）。

コマンドで行う場合（PowerShell）:
```powershell
cd android
.\gradlew.bat :core:test
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
```

Gradle 9.4.1 / AGP 9.2.1 / Kotlin 2.4.21 を固定している。JDK 25 で JVM テストを実行可能で、生成クラスは Java 17 をターゲットにする。Android Studio では同梱 JBR も利用できる。

SDK は `local.properties` の `sdk.dir`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`、Windows 既定の `%LOCALAPPDATA%\Android\Sdk` の順に探す。Windows 既定だけで見つかった場合は、無視対象の `local.properties` を生成する。
SDK がない場合は `:app` を構成から外し、`:core:test` だけを実行できる。SDK を入れた後に Gradle を再同期すると `app` が現れる。SDK のある環境でも `-PcoreOnly=true` で JVM モジュールだけを構成できる。

## 使い方（メイン画面）
- 起動すると魔女があいさつする。画面下のヒント（例「⇣ うなずく ＝はい」）の動作で答える。字幕とヒントは常に文字とアイコンの両方で出る
- 使える動作: うなずく／首をふる（スマホを手前に倒す・左右にひねる）、左右に傾ける、画面か背面をトントン、なでる、長押し、軽くゆらす、思いきり振る、ふせて置く、持ち上げる
- スマホを傾けると彼女は低い方へすべり、画面の端にぶつかると振動する
- 右上の ⚙ で設定: デモモード（30 秒の台本）、振動 ON/OFF と強さ、字幕の大きさ、話しかけてくる頻度、読み上げ。開発者向けに「ジェスチャー練習」と「触覚ベンチ」もここから開く

## 30 秒デモの流れ（⚙ → デモモード ON）
1. 内側から 2 回ノックされる → 画面か背面を 2 回トントンして返事
2. 「右と左、どっちが好き？」→ 右に傾ける（彼女がすべって端にぶつかり、ドンと振動）
3. 「ちょっと待って〜！」→ 思いきり振る（目を回す。「今の覚えたからね」）
4. 数秒後、画面の内側からドンと仕返しされる → なでて仲直り（ごろごろ）
答えないと 1 回だけヒント付きで聞き直し、それでも答えなければ次へ進む。

## T01 で使える画面
- ⚙ → 触覚ベンチで開く
- ベンチで12クリップ、ノック1〜4回、衝突強度、Tier、全体強度、OFF を試せる。能力と実際のレンダリング Tier はレポートとログで確認する
- 「はい／いいえ ブラインドテスト」を ON にしてランダム再生後に回答する。OS が振動を抑制していないことを先に確認する
- `adb logcat -s VtubePhone` で JS→ネイティブ遅延と呼び出し時間を確認する。JS の片道遅延は時計同期による推定で、±RTT/2 の誤差を併記する。モーターの物理的な開始時間ではない
- [実装と API の対応](android-haptics.md)も参照

## キャラクターモデル
- T03 は Live2D。`android/local.properties` の `vtubephone.modelDir` に、1 個の `.model3.json` と参照先を含むフォルダを指定する。Java Properties の Unicode エスケープを復元して読み込む。モデル・キー・Core はコミットしない。
- `vtubephone.maxTextureSize=4096` が既定。長辺をこの上限へ縮小し、縦横比とアルファを保持する。元ファイルは変更しない。
- `:app:prepareLive2dAssets` が `app/build/generated/live2d/assets/models/default/` にモデルを生成する。AGP Variant API で assets に接続済みなので、通常の Run/assemble から自動実行する。日本語の参照名は保持し、JS が各パス成分を URL エンコードする。ルートの設定名だけ `model3.json` に統一する。
- モデル未設定、参照不足、テクスチャ変換失敗時は生成モデルを取り除き、`web/live2d-assets.json` に表示用エラーを保存する。APK のビルドは継続し、起動時に理由を表示する。
- VRM とアプリ内モデル選択は後続対応。

### Cubism の取得とライセンス

- レンダラーは [公式 CubismWebFramework 5-r.4](https://github.com/Live2D/CubismWebFramework/tree/5-r.4)（コミット `8df84780f2aa1298f3b30965cdae143e049f3c8e`）。所有モデルを実際に Core + Framework で開き、moc3 version **5**、617 drawable を生成できたため選定した。WebGL、マスク、パラメータ、Cubism 物理を公式実装に任せ、PixiJS 依存は追加していない。
- `assets/web/vendor/cubism/framework.js` は esbuild **0.25.5** による ES module の事前バンドル。同梱 LICENSE.md に従う。再生成だけ `./tools/vendor-live2d.ps1`（リポジトリのルートで実行）を使う。アプリのビルド・実行時に npm は不要。
- **Core はプロプライエタリ**。[公式ダウンロード案内](https://www.live2d.com/en/sdk/download/web/) の配布スクリプトが示す [Cubism Core 配布 URL](https://cubism.live2d.com/sdk-web/core/05/live2dcubismcore.min.js) から Gradle が取得する。Core の申告バージョンは **5.1.0**。SHA-256 は `25ae938cb4fe282ce189b357bcc97e603d1e1f7ec78bf04150d401c23cdc792f` に固定。URL が更新されても別内容を黙って使わない。
- キャッシュは `app/build/live2d-cache/`、APK 用は `app/build/generated/live2d/assets/web/vendor/`。初回だけネット接続が必要。キャッシュがあれば `--offline` でも生成可能。取得失敗・ハッシュ不一致・キャッシュなしの offline はエラー表示用 manifest を生成し、次のビルドで再試行する。
- Core は [Live2D Proprietary Software License](https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_en.html)、Framework は [Live2D Open Software License](https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html) の対象。配布前には [SDK Release License](https://www.live2d.com/en/sdk/license/) と所有モデルの利用条件も確認する。

### PM 向け表示 API（T03 の配管）

`window.avatar` は読み込み成功後に公開する。準備通知は `window.addEventListener('vtubephone:avatarready', e => { const avatar = e.detail; ... })`。すでに `window.avatar` がある場合は直ちに利用できる。身体動作・表情名のマッピング・反射はこの層に実装しない。`#caption`、`#options` は空の DOM。

| API | 契約 |
|---|---|
| `setParam(id, value, weight=1)` | モデルの素の ID。weight を 0〜1 に制限し、毎フレーム初期値から適用。設定を保持し、NaN/Infinity は拒否 |
| `await applyExpressionFile(name)` | model3 の Expression Name または File。オーナーモデルの未登録 `.exp3.json` もファイル名の stem（`x` など）で取り込む。exp3 の Add/Multiply/Overwrite を即時に重ねる低レベル API。フェードや感情の選択は行わない。同名は置換 |
| `clearExpressions()` | 全ファイル表情を解除。読み込み途中の表情も無効化。加算/乗算はフレームをまたいで蓄積しない |
| `setPhysicsGravity(x,y)` | Cubism Physics の options.gravity へそのまま渡す。端末 up からの符号変換・動作設計は PM 側 |
| `setTransform({x,y,rotation,scale})` | 初期位置からの移動（CSS px、右/下が正）、画面中心を軸に時計回りの radians、初期フィット倍率に対する正の scale。省略キーは保持 |
| `getBounds()` | canvas 左上を原点とする CSS px の `{x,y,width,height}`。初期表示メッシュの概算矩形に、移動・回転・倍率を反映した AABB。個別パーツのアニメーション追跡はしない |
| `hitTest(x,y)` | 同じ CSS 座標で概算矩形に入るか boolean。回転・倍率も逆変換して判定 |
| `onFrame(cb)` | `cb(deltaSeconds,timeMs)` をパラメータ適用前に呼ぶ。戻り値を呼ぶと購読解除。dt は最大 0.05 秒。非表示時停止、再開最初は dt=0 |

フレーム順: 初期パラメータを復元 → onFrame → setParam → exp3 のブレンド → Cubism Physics → model.update → WebGL 描画。自動まばたき・呼吸・アイドルモーションは入れていない。

ネイティブは `SensingService.up` を Choreographer で最大 60Hz の `{type:'motion',up:[x,y,z]}` として送る。座標は Android 端末座標（右/上/画面から手前）。JS は `vtubephone:motion` の CustomEvent で通知し、`e.detail.up` から取得する。Activity pause 中とページの再読み込み中は送信停止。[WebMessageListener](https://developer.android.com/reference/androidx/webkit/WebViewCompat) の main frame と appassets origin の検証を維持する。

pointerdown/move/up/cancel は `{type:'touch',phase,pointerId,x,y,timeMs,ageMs,inAvatar,bounds}` として返す。CSS px、元イベントの `timeStamp` を保持する。`ageMs=performance.now()-timeStamp` を `SensingService.onWebTouch` に渡し、既存 T02 の判定へ配線する。pointer capture を失った場合も cancel を送る。物理会話・表情・触覚の選択は PM の責務。

検証（ルートで実行）:
```powershell
node --test android/app/src/test/js/*.test.mjs
node tools/verify-live2d-model.mjs   # 生成済みの所有モデル・Core で実際の moc3 v5 読み込みを確認
./tools/test-live2d-assets.ps1      # 異常系を確認後、通常の assembleDebug へ戻す
```

## 困ったとき
- 端末が表示されない: USB ケーブルがデータ通信に対応しているか確認し、USB デバッグを OFF→ON する
- 振動しない: アプリの設定で振動が OFF になっていないか確認する。端末側の「メディアのバイブ」の強さも確認する

## AI と読み上げ（任意）
- `android/local.properties` に `GEMINI_API_KEY=...`（https://aistudio.google.com/apikey）を書いてビルドすると、会話の脳が Gemini 3.5 Flash-Lite になり、右下に🎤（押している間だけ録音）が出る
- 読み上げは ⚙ → 「読み上げ」を ON。`VOICEVOX_API_KEY=...`（https://su-shiki.com/api/）があれば高速版、なければ低速版（数秒遅れる）
- キーが空でもアプリは完全に動く（ルールの脳で会話し、字幕だけで話す）。キーはコミットしない
