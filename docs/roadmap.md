# ロードマップ（PM 管理）

構成は Kotlin ネイティブ + WebView。Codex が実装し、PM がレビュー（テストとビルドを自分で再実行）し、オーナーが実機で受け入れる。テスト端末は Galaxy SM-S942Q（Android 16、触覚は Tier B、プリミティブ 8 種すべてに対応）。

| # | タスク | 仕様 | 状態 |
|---|---|---|---|
| T01 | Android 基盤 + 精密触覚エンジン | `tasks/T01-android-haptics.md` | ✅ 実機で動作。はい／いいえの識別も OK |
| T01 rev2 | 触覚の語彙の拡張（18 種、プリミティブ直接指定、シーケンサー） | `tasks/T01-rev2-haptic-vocabulary.md` | ✅ 18 種。感情でやわらかい波形（Tier C）に切り替え |
| T02 | センサーと身体言語の入力 | `tasks/T02-android-sensing.md` | ✅ PM 実装。合成データのテスト 25 件。閾値は実機で調整（ジェスチャー練習画面） |
| T03 | Live2D「魔女」の表示と「箱の中の身体」 | `tasks/T03-live2d-avatar.md` | ✅ 配管（Codex）+ 身体・表情・動作（PM、`assets/web/life/`） |
| T04 | 脳と物理会話（オフライン）+ 感情ごとの描画モード + 30 秒デモ | `tasks/T04-brain-dialog.md` | ✅ PM 実装。30 秒デモの通しシミュレーションを含むテスト 13 件 |
| T05 | Gemini 3.5 Flash-Lite + 任意の音声入力 + su-shiki VOICEVOX | `tasks/T05-gemini-voice.md` + `T05-dispatch.md` | 配管は Codex、人格・プロンプト本文は PM（`core/llm/Persona.kt`） |
| T06 | 仕上げ（閾値の調整、README、通しのデモ） | ― | 未着手 |

## オーナーの作業
- [x] Android Studio のインストール、実機の接続
- [ ] `android/local.properties` に `GEMINI_API_KEY=` を書く（https://aistudio.google.com/apikey で取得）
- [ ] （任意）`VOICEVOX_API_KEY=` を書く（https://su-shiki.com/api/ で取得。空の場合は低速版を使う）
- [ ] Live2D Cubism Core のライセンス（https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_jp.html）を確認する
