# vtubephone

スマホの中に住んでいる AI ライバー「まじょっこ」。**スマホの動きと振動で会話する**のが主役で、声や文字入力は任意。
声を出しにくい人や、文字を打ちにくい人でも話せるようにする。

- うなずく・首をふる・傾ける・トントン・なでる・振るで答えると、彼女は表情・身体の動き・振動（リニアモーター）で返す
- 同じ操作でも、彼女の気分によって意味が変わる。激しく振られたことは覚えていて、あとで内側から「ドン」と仕返しする
- 会話の脳はオフラインのルールか Gemini 3.5 Flash-Lite。読み上げは su-shiki の WEB 版 VOICEVOX（任意）

## 動かす
[docs/dev-setup.md](docs/dev-setup.md)（Android Studio で `android/` を開いて ▶ Run）。使い方と 30 秒デモの流れも同じページにある。

## 構成
- `android/core` — JVM の純粋ロジック（触覚クリップ、ジェスチャー認識、脳、LLM のプロンプトとパース）。JUnit でテストする
- `android/app` — Android（VibrationEffect、センサー、WebView、音声）
- `android/app/src/main/assets/web` — Live2D の表示（`avatar/`）と身体・表情・字幕（`life/`）
- 設計: [docs/product.md](docs/product.md)、[docs/architecture.md](docs/architecture.md)、[docs/haptic-vocabulary.md](docs/haptic-vocabulary.md)、[docs/roadmap.md](docs/roadmap.md)

モデル、API キー、Cubism Core はリポジトリに含めない（`local.properties` とビルド時の取得）。
