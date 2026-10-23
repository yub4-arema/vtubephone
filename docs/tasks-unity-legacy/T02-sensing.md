# T02: センサーと身体言語の入力

## 目的
`docs/product.md` の「入力ジェスチャー」を**高精度かつ誤検出少なく**認識する。会話の入力チャネルそのものになるので、取りこぼしよりも**誤爆**（意図しないジェスチャーの検出）のほうが体験を壊す。閾値は実機で調整する前提とし、**実データの記録と再生の仕組み**を最初から作る。

## 作業内容

### 1. ネイティブのセンサー取得（`Assets/Plugins/Android/MotionSensor.java`）
- `SensorManager` で次を `SENSOR_DELAY_FASTEST` で購読する: `TYPE_ACCELEROMETER`、`TYPE_GYROSCOPE`、`TYPE_GRAVITY`、`TYPE_LINEAR_ACCELERATION`、`TYPE_GAME_ROTATION_VECTOR`。存在しないセンサーは能力レポートに記録する
- サンプルは `SensorEvent.timestamp`（ns）つきでロックフリーかロックの軽いリングバッファに溜める
- C# からは**毎フレーム 1 回の JNI 呼び出し**（`drain()` が float[] か double[] をまとめて返す）で取り出す。サンプルごとに JNI を呼ばない
- 実効サンプリングレート（センサーごとの Hz）を計測し、能力レポートに含める
- `start()`、`stop()` を用意し、アプリが pause したら止める
- Editor 用には `SimulatedMotionSource`（キーボードやマウスで傾き、振り、ノックを模擬）を用意する

### 2. Core（純粋 C#。`VtubePhone.Core`）
- `MotionSample`（tNs、センサー種別、x、y、z（、w））と `GestureEvent`（GestureId、tNs、方向、強さ 0..1、count、intervalsMs[]、confidence）
- `GestureRecognizer`: サンプル列を受け取り GestureEvent を出す、状態を持つ認識器。各ジェスチャーは個別の検出器クラスに分ける
  - **基準姿勢**: 静止に近い状態が続いたときの重力ベクトルの移動平均を「ニュートラル」とする。傾きは、端末の絶対角ではなく**ニュートラルからの差**で判定する（寝転んで持っていても使えるようにするため）
  - `tilt_*`: ニュートラルから roll または pitch が閾値（初期値 20°）を超え、400ms 以上保たれたら発火する。戻るまで再発火しない
  - `nod`: 端末の横軸まわりの角速度が、往復 2 回以上 1.2 秒以内に振れたら発火する。その間、ほかの軸まわりの回転が小さいことを条件にする
  - `headshake`: **重力軸まわり**のひねり（角速度を重力方向へ射影したもの）が、往復 2 回以上 1.2 秒以内に振れたら発火する
  - `shake_light` / `shake_hard`: 線形加速度の窓内 RMS とピーク値で判定する。激しい振りでは、そのあとの数百 ms は傾き、うなずき、首振りの検出を抑制する
  - `flip_down`: 重力の向きから「画面が下向き」で 0.7 秒以上静止していることを判定する。符号の扱いはテストで固定する
  - `still` / `pickup`: 加速度の分散が小さく、ほぼ水平な状態が 1.5 秒続いたら still。そこから動き出したら pickup
  - `back_knock`: 加速度（z 軸主体）をハイパスに通して得た鋭いスパイクで判定する（持続 30ms 以内、不応期 80ms）。**画面タッチの前後 ±80ms は抑制する**（画面を叩いても加速度にスパイクが出るため）。振りや歩行の最中は無効にする
- `KnockSequencer`: 画面のタップと背面ノックを、打鍵間隔 600ms 以内なら 1 つの列にまとめ、700ms 無音で `knock(count, intervalsMs[])` を確定させる
- `RhythmMatcher.Match(target, actual)`: 回数が一致すること、正規化した間隔の誤差が ±35% 以内であることを判定する。T04 でノックの返事の判定に使う
- `TouchGestureRecognizer`: タッチ列（指 ID、位置、**event time**）から `tap`、`hold`（500ms 以上で移動が小さい）、`stroke`（アバター領域を一定以上の距離なぞる）を判定する。アバター領域は `Rect` で与える（T03 までは画面中央の矩形とする）
- **調停**: 同じ時刻帯で競合するジェスチャーは優先順位とクールダウンで 1 つにする。優先順位は shake_hard > flip_down > nod/headshake > tilt。表で定義し、テストで固定する
- すべてのパラメータは `GestureConfig`（シリアライズ可能）に集約し、JSON で上書きできるようにする

### 3. 記録と再生（チューニング基盤。必須）
- `MotionRecorder`: 生サンプルとタッチイベントを `Application.persistentDataPath/recordings/*.csv` に保存する。ラベル（ユーザーが「今からうなずく」と選んでから実行する）も記録する
- `tools/CoreTests` 側に **CSV を再生して GestureRecognizer の出力を検証するテスト基盤**を作る。`tests/fixtures/motion/*.csv` に置いた実データに対し、「ラベルどおりのジェスチャーが出ること」「ラベル外のジェスチャーが出ないこと」を確認する。実データがまだないので、まずは合成データで作る
- `adb pull` で記録を取り出す手順を `docs/dev-setup.md` に追記する

### 4. GestureLab シーン（SceneBuilder で生成）
- ライブ表示: 重力ベクトル、ニュートラルからの roll と pitch、角速度、線形加速度の RMS、背面ノック用のハイパス値（簡易グラフ）
- 検出したイベントのログ（時刻、ID、強さ、confidence）と、ジェスチャーごとのカウンタ
- **ラベルつき記録**: ジェスチャーを選んで記録ボタンを押し、実行してから停止する
- **練習モード**: 「うなずいてください」などのお題を出し、正しく検出されたら T01 の `yes`、違うジェスチャーなら `no` を鳴らす。検出率と誤検出数を集計する
- 実効サンプリングレートを表示する
- T01 の HapticsBench シーンと行き来できるようにする（Build Settings に両方を登録する）

## 受け入れ基準
1. `dotnet test tools/CoreTests/CoreTests.csproj` がすべて通る（新しいテストファイルを csproj に含めること）。最低限、次をテストする
   - ジェスチャーごとの正例（合成データ）と負例。負例の例: 机に置く動作で `back_knock` や `pickup` が出ないこと、歩行に相当する周期的な揺れで `nod` が出ないこと、ゆっくり傾けるだけで `shake` が出ないこと
   - ニュートラルを変えても（寝転んだ姿勢でも）傾きが正しく出ること
   - 画面タップの直後には `back_knock` が抑制されること
   - KnockSequencer と RhythmMatcher
   - 調停の優先順位
   - CSV の再生基盤（合成データの CSV で 1 本）
2. `tools/verify-static.ps1` で `MotionSensor.java` が API 31 と API 36 に対してコンパイルできる
3. Unity でのコンパイルは、可能なら `artifacts/compile` の方式で確認する。ライセンスが有効なら EditMode テストとシーン生成も行う

## 範囲外
アバター、AI、会話ロジック（T04 で「期待しているジェスチャー」による感度の調整を入れる予定。今は API の差し込み口だけ用意してよい）
