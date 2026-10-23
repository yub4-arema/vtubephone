# T03: Live2D アバターの表示と「箱の中の身体」

> **担当分け（オーナーの指示）**: ジェスチャーと表現（身体の動き、表情の設計、反射、触覚の選び方）は PM（Claude）が直接実装する。
> **Codex の担当は「表示の配管」だけ**とする。具体的には次のとおり。
> - モデルの取り込み（Gradle タスクとテクスチャの縮小）
> - Cubism Core の取得
> - レンダラーの選定と組み込み
> - 低レベルの JS API: `avatar.setParam(id, value, weight)`、`avatar.applyExpressionFile(name)`、`avatar.clearExpressions()`、`avatar.setPhysicsGravity(x, y)`、`avatar.setTransform({x, y, rotation, scale})`、`avatar.getBounds()`、`avatar.hitTest(x, y)`、`avatar.onFrame(cb)`
> - ブリッジ: ネイティブから 60Hz で `{type:"motion", up:[x,y,z]}` を受け取り、JS の pointer イベントを `{type:"touch", ...}` で送り返す
>
> 下の「身体の表現」「表情 API」「簡易の反射」は PM が実装するので、**Codex は実装しないこと**。字幕とヒントは、DOM の空要素（`#caption`、`#options`）を置くだけでよい。

## 目的
オーナーの Live2D モデル「魔女」を WebView で表示する。スマホの傾きや振りに身体が反応し、画面の縁にぶつかると衝撃に比例した振動が来るようにする。

## モデルの取り込み（ビルド時）
- `local.properties` の `vtubephone.modelDir`（Unicode エスケープ済みのパス。`Properties.load` で読めば正しく復元される）からモデルフォルダを読み込み、**`app/build/generated/...` の assets に `models/default/` としてコピーする Gradle タスク**を作る。src には置かず、コミットもしない
- コピーするときに**テクスチャを縮小**する。元は 8192×8192 と 4096×8192。長辺を最大 4096 にする（`vtubephone.maxTextureSize` で変更可）。Live2D の UV は正規化座標なので、縮小しても問題ない。Java の ImageIO で処理してよい
- ファイル名に日本語を含む（`魔女.model3.json` など）。URL エンコードとアセット名の扱いに注意する。必要ならコピー時に ASCII 名へ変え、model3.json 内の参照も書き換える
- `modelDir` がない場合は「モデル未設定」と表示する（ビルドは失敗させない）

## Live2D ランタイム（WebView）
- **Cubism Core for Web**（`live2dcubismcore.min.js`）は Live2D 社のプロプライエタリライセンスのため、**リポジトリにコミットしない**。Gradle タスクで公式の配布元から取得して generated assets に置く。取得元の URL とライセンスの注意を `docs/dev-setup.md` に書く
- このモデルは moc3 v5（Cubism 5）。これを読めるレンダラーを選ぶ。候補は pixi-live2d-display 系（フォークを含む）か、公式の Cubism SDK for Web Framework を esbuild で一度だけバンドルしたもの。**実際に moc3 v5 を読めることを確認したうえで選ぶ**。選んだ理由を報告に書く
- 選んだライブラリはバージョンを固定して `assets/web/vendor/` に同梱する（Cubism Core は除く）。ビルド工程を使う場合も、生成物をコミットし、実行時やデプロイ時に npm を必要としないこと

## 身体の表現
- **箱の物理**（JS、自作の簡易 2D で十分）: モデル全体を剛体として扱う。重力ベクトル（ネイティブから 60Hz で送られる）で画面の中を滑らせ、少し回転もさせる。画面の縁で跳ね返り、減衰させる。普段は中央へ戻る弱いバネで落ち着かせる
- **衝突したら** ブリッジで `{type:"bump", impact:0..1}` を送り、ネイティブの `HapticsService.playBump(impact)` を呼ぶ。impact は法線方向の相対速度から計算する。振動の不応期は 120ms
- **Cubism 物理の重力**: 端末の重力方向を Cubism の物理演算の gravity に渡し、髪や服の揺れが実際の傾きに従うようにする
- **パラメータ**: 傾きに応じて `ParamBodyAngleX/Z` と `ParamAngleX/Y/Z` を動かす（体が慣性で遅れ、頭はユーザーのほうを向き直す）。まばたき（EyeBlink グループ）と呼吸
- **表情 API**（JS）: `setExpression(name)`。name は `neutral`、`happy`（EyeSmile と MouthForm）、`love`（x）、`star`（xx）、`cry`（ku）、`angry`（sq と Param53）、`gloom`（h）、`pout`（鼓脸 Param21）、`dizzy`（目を回す。パラメータの組み合わせで作る）、`surprise`、`sleepy`。加えて `wave()`（招手 Param28 と 30）
- **口パク API**: `setMouth(open 0..1)`（T05 の音声で使う）
- **字幕**: 画面下部に 20 字程度の吹き出し。`say(text, durationMs)`
- **ジェスチャーのヒント**: `showOptions([{gesture, label}])` で、画面上に「⤴ うなずく = はい」のようなアイコンと説明を出す
- **タッチ**: モデルのヒット領域（頭と体の概算矩形）上の pointer イベントを、T02 の TouchGestureRecognizer（ブリッジ経由）に送る。なでられている間は `happy` の表情にし、`purr` を鳴らす

## 簡易の反射（T04 の脳ができるまでの仮実装）
- `shake_hard` → `dizzy` の表情と振動
- `stroke` → `happy` と `purr`
- `flip_down` → `sleepy` の表情。画面を暗くする
- `pickup` → `surprise` → `wave()` と `call`

## 受け入れ基準
1. `./gradlew :app:assembleDebug` が成功する。Cubism Core とモデルは generated assets に入る
2. エミュレータは使えない前提とする。代わりに WebView 側の JS を **Node でテストできる単体部分**（物理演算、衝突判定、impact の計算）に分けてテストする（`node --test`）。描画は実機で確認する
3. モデルなし、Cubism Core の取得失敗、テクスチャ縮小の失敗、のいずれでもアプリが落ちない
