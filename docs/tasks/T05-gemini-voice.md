# T05: Gemini（会話の脳と任意の音声入力）+ VOICEVOX（任意の読み上げ）

## 方針（オーナーの指定）
- LLM は **Gemini 3.5 Flash-Lite**（モデル ID `gemini-3.5-flash-lite`）。入力にテキストと音声を受け付け、出力はテキスト。音声認識も Gemini に任せる
- 読み上げは **su-shiki の WEB 版 VOICEVOX API**
- サーバーは立てない。**アプリから直接 API を呼ぶ**（オーナーの個人用デバッグビルドなので、キーは `local.properties` から BuildConfig に入れる。キーはコミットしない）
- **キーがない場合**は自動で T04 の RuleBrain（オフライン）と読み上げなしで動く。アプリは落ちない

## Gemini
- REST: `POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent`。ヘッダーは `x-goog-api-key`。**正確な仕様は https://ai.google.dev/gemini-api/docs で確認する**（structured output の `responseMimeType: application/json` と `responseJsonSchema`（または `responseSchema`）、音声の `inline_data`、対応する音声フォーマット）
- `LlmBrain`: T04 の RuleBrain と同じインターフェースを実装する。入力は、キャラクター設定（魔女。口調は短く、かわいく、少しいたずら好き）、身体言語の説明（使えるジェスチャーと触覚フレーズと表情の一覧）、State、EventLog、直前の DialogOutcome、（あれば）ユーザーの音声。出力は `AgentTurn` の JSON（スキーマで強制する）
- **プロンプトの原則**: 返事はジェスチャーで答えられる質問を中心にする（options を付ける）。say は 20 字以内。haptic と expression を必ず選ぶ。同じ質問の繰り返しを避ける
- 応答の検証: スキーマ外の値（存在しない gesture や haptic の id）は捨てるか補正する。失敗したり 4 秒でタイムアウトしたりしたら、RuleBrain のターンで代替する
- LLM を待つ間も Reflex（端末内の即時反応）は動き続ける。待ち時間が長いときは「考え中」の小さな表情を出す
- 呼び出しの頻度を制限する（自発的なターンは InitiativeScheduler に従う。ユーザーのジェスチャーをきっかけにする場合も最短 1.5 秒の間隔を空ける）

## 音声入力（任意。物理動作が主なのは変わらない）
- 画面の🎤ボタンを押している間だけ録音する（`AudioRecord`、16kHz、mono、PCM → WAV）。最長 10 秒
- WAV を Gemini の `inline_data`（`audio/wav`）として、その会話ターンの入力に含める（文字起こしと応答を 1 回の呼び出しで行う）
- `RECORD_AUDIO` パーミッションは、最初に🎤を押したときに要求する。拒否されたら🎤を隠す

## 読み上げ（任意。設定で ON にしたときだけ）
- `VOICEVOX_API_KEY` がある場合は高速版: `https://deprecatedapis.tts.quest/v2/voicevox/audio/`（POST、`text`、`key`、`speaker`）。レスポンスの形式は実際に確認して実装する
- キーがない場合は低速版: `https://api.tts.quest/v3/voicevox/synthesis?text=...&speaker=...` → `audioStatusUrl` を `isAudioReady` になるまでポーリング → `mp3DownloadUrl`。`retryAfter` を守る
- エラー（`notEnoughPoints`、`invalidApiKey`、`retryAfter`）のときは、字幕だけで続ける
- 再生中は振幅から口パク（`setMouth`）を同期させる（`Visualizer` か、デコードした PCM の RMS で）
- speaker ID は設定で選べるようにする（初期値は落ち着いた女性の声）
- 利用規約: 個人利用の範囲にとどめ、`*.tts.quest` のエンドポイントだけを使う

## 受け入れ基準
1. `./gradlew :core:test` が通る。追加するテストは次のとおり
   - プロンプトの組み立て
   - AgentTurn JSON の検証と補正（不正な ID、長すぎる say）
   - タイムアウト時に RuleBrain へ切り替わること
   - TTS の各レスポンス（成功、retryAfter、エラー）の処理（HTTP はモックする）
2. `./gradlew :app:assembleDebug` が成功する（キーが空でも成功すること）
3. キーが空の状態で、アプリがオフラインモードとして完全に動く
