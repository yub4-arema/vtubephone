# T05 Codex 担当分（配管のみ）

仕様の全体は [T05-gemini-voice.md](T05-gemini-voice.md)。そのうち **Codex は通信・音声・パースの配管だけ**を実装する。キャラの台詞・プロンプト本文・表情や反射は PM の担当で、次のファイルはすでにある。

- `core/.../brain/`（Turn.kt, BrainEngine.kt, RuleBrain.kt, DemoScript.kt …）: **編集しない**。`TurnSource`（同期 `next(ctx): AgentTurn?`）と `AgentTurn` を使う
- `core/.../llm/Persona.kt`: **編集しない**。キャラ設定・ジェスチャー／触覚／表情／動作の説明・出力ルール。プロンプトの組み立てはこの文字列を使う
- `app/.../ui/MainActivity.kt`, `assets/web/**`, `app/.../brain/**`: **編集しない**（PM が BrainService とUIを配線中）

## 作るもの

### core（JVM・JUnit でテスト）: `com.vtubephone.core.llm`
- `PromptBuilder`: `systemInstruction()`（Persona.character + 一覧 + rules）と `userTurn(ctx: BrainContext): String`（State.describe()、EventLog.render()、トリガーの説明。Trigger ごとに「何が起きたか／何をしてほしいか」を 1〜2 行で）
- `TurnSchema`: Gemini の structured output に渡す JSON Schema（say, expression enum, haptic{id enum, count, intensity}, motion enum, options[≤3]{gesture enum, meaning, label, knockCount}, timeoutSec, stateDelta）。enum は Expression/Motion/GestureId（Persona.gestures のキー）/HapticClipId の wire 名から生成
- `TurnJson.parse(json): AgentTurn?`: 検証と補正。未知の enum 値は捨てる（gesture 不明の option は除外、haptic 不明は null、expression 不明は Neutral）。say は 40 文字で切る。options は 3 個まで。knockCount 1..4、timeoutSec 4..15、stateDelta 各 ±0.3 に clamp。tag は "llm"
- `GeminiClient(transport: HttpTransport, apiKey, model = "gemini-3.5-flash-lite")`: generateContent。`systemInstruction`、`contents`（テキスト + 任意の `inline_data` audio/wav base64）、`generationConfig`（responseMimeType application/json + schema、temperature 0.9 程度）。レスポンスの candidates[0].content.parts[*].text を連結して TurnJson へ。**正確なフィールド名は https://ai.google.dev/gemini-api/docs で確認**
- `LlmTurnSource(client, fallback: TurnSource, timeoutMs = 4000, minIntervalMs = 1500, clock)`: TurnSource 実装（呼び出しはバックグラウンドスレッドから来るので blocking でよい）。Start/Timeout(options なし) は fallback に任せてよい。失敗・タイムアウト・null・間隔不足は fallback。音声トリガーは間隔制限を無視
- `TtsClient(transport, apiKey?)`: su-shiki。キーあり→高速版 v2、なし→低速版 v3（ポーリング、retryAfter 遵守、最大 15 秒）。戻り値は音声バイト列（mp3/wav）か失敗理由。`notEnoughPoints`/`invalidApiKey`/`retryAfter` を扱う
- `HttpTransport` interface（`post(url, headers, body): HttpResult` / `get`）でモック可能に
- テスト: プロンプト組み立て、TurnJson の補正、LlmTurnSource の fallback（例外・タイムアウト・不正 JSON）、TTS 各レスポンス（モック）

### app: `com.vtubephone.app.voice`
- `UrlConnectionTransport`（HttpURLConnection、タイムアウト付き、メインスレッド禁止）
- `VoiceRecorder`: AudioRecord 16kHz mono PCM16 → WAV バイト列。`start()` / `stop(): ByteArray?`、最長 10 秒で自動停止。パーミッションの確認は呼び出し側（PM）
- `VoicePlayer`: `speak(text, speaker)` → TtsClient → MediaPlayer で再生。再生中 ~30Hz で `onMouth(open: Float 0..1)` コールバック（Visualizer は RECORD_AUDIO が要るので、**デコードした PCM の RMS か MediaExtractor/MediaCodec** を推奨。実装しやすい確実な方法を選んで理由を報告）。`stop()`。失敗時は何もしない（字幕だけで続く）
- `build.gradle.kts`: `buildConfig = true`、`GEMINI_API_KEY` / `VOICEVOX_API_KEY` を local.properties から BuildConfig へ（空でもビルド成功）。`INTERNET` と `RECORD_AUDIO` パーミッションを Manifest に追加
- `VtubePhoneApplication` に `val voice: VoiceServices by lazy`（llm: LlmTurnSource?（キーなしは null）, recorder, player）を追加してよい。**既存の haptics / sensing 行は残す**

## 受け入れ基準
1. `./gradlew :core:test` 成功（既存テスト含む）
2. `./gradlew :app:assembleDebug` 成功（キーが空でも）
3. キーなしで LlmTurnSource を作らない／TtsClient は低速版で動く設計
4. 報告は AGENTS.md の形式。実 API を叩いた確認ができたなら結果を書く（キーがなければ ⏸）
