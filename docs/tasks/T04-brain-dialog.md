# T04: 脳と物理会話（オフライン）

## 目的
声も文字も使わずに、ジェスチャーと振動と表情と字幕だけで会話が成立するようにする。AI（T05）がなくても動く**ルールベースの脳**と**台本モード**を作る。T05 では、この脳の「次のターンを決める部分」を LLM に差し替える。

## core（JVM、テスト可能）
- `CharacterState`: mood(-1..1)、energy、intimacy、curiosity（いずれも 0..1）、grudge（仕返しのための記憶。0..1）。時間とともに基準値へ戻る
- `EventLog`: 直近 30 件の出来事（ジェスチャー、選択の結果、ノックの成否、衝突、放置）を、自然言語の短い文で保持する。T05 のプロンプトで使う
- `AgentTurn`（T05 の LLM 出力と同じ形）:
  ```
  say: String (20 字以内), expression: String, haptic: {id, count?, intensity?}?, motion: String?,
  options: [{gesture: GestureId, meaning: String, label: String}] (最大 3), timeoutSec: Int?,
  stateDelta: {...}?
  ```
- `ReflexTable`: (GestureEvent, State) → 即時反応（表情、振動、身体の動き）。**同じジェスチャーでも状態によって反応を変える**。例: shake_hard を受けたとき、mood が高ければ「きゃー！」と楽しそうな反応、低ければ angry にして grudge を加算する
- `DialogController`: AgentTurn を実行し、options を待ち受け、一致したジェスチャーかタイムアウトを `DialogOutcome` として返す。待ち受け中は該当ジェスチャーの検出感度を少し上げる（T02 の差し込み口を使う）
- `RuleBrain`（オフラインで次のターンを決める）: 状態と出来事から、事前に用意したターンのプールを選ぶ。最低限、以下を用意する
  - 体調を聞く（はい／いいえ → なでて、など）
  - 2 択（右か左か）
  - ノックの呼びかけ（knock(n) → 同じ回数で返事してもらう。RhythmMatcher で判定する）
  - 数を聞く（「どれくらい？ 1〜3 回ノックして」）
  - 構ってほしい（call → pickup や stroke を待つ）
  - 仕返し（grudge が高く、しばらく後 → `thud` と画面に迫る動き）
  - 寝る（flip_down → おやすみ）
- `InitiativeScheduler`: 放置時間、energy、前回の自発行動からの経過時間で、自発的なターンを発火させる。頻度の上限を守る（初期値は 45 秒に 1 回まで）
- **感情 → 触覚の描画モード**（オーナーの提案「感情に応じて Tier を変えてもいい」）: クリップごとに `"render": "auto" | "primitive" | "waveform"` を指定できるようにする。もやもやした感情やなだらかな感情（purr、sleepy、sigh）は waveform のなめらかさ、はっきりした感情（yes、knock、surprise、angry）は primitive の鋭さ、のように**表現のために意図的に描画モードを選べる**ようにする。HapticRenderer にこの優先指定を実装し、テストで固定する

## 台本モード（30 秒デモ。`docs/product.md` のシナリオ）
- 設定で ON にすると、RuleBrain の代わりに固定の AgentTurn 列を流す。ジェスチャーの判定、振動、表情は本番と同じ経路を通す

## app
- `BrainService`: GestureBus を購読し、Reflex → DialogController → RuleBrain（あるいは T05 の LLM）の順に配線する
- WebView に `say`、`setExpression`、`showOptions`、`motion` を送る
- 設定画面（Compose）: 台本モード、振動の強さと OFF、自発行動の頻度、字幕のサイズ、デバッグ表示

## 受け入れ基準
1. `./gradlew :core:test` が通る。追加するテストは次のとおり
   - Reflex の状態による分岐
   - DialogController の options 一致とタイムアウト
   - ノックの返事の判定
   - InitiativeScheduler の頻度上限
   - grudge から仕返しへの流れ
   - render の優先指定
   - 台本の 30 秒シナリオを模擬ジェスチャー列で最後まで通すシミュレーション
2. `./gradlew :app:assembleDebug` が成功する
