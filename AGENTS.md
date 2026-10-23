# AGENTS.md — Codex 向け開発ルール

あなた（Codex）はこのリポジトリの**開発担当**です。PM（Claude）が `docs/tasks/` にタスク仕様を書くので、1 タスクずつ実装してください。

## 必読
- `docs/product.md`: 何を作るか（物理動作が主の会話、身体言語、触覚フレーズ）
- `docs/architecture.md`: Kotlin + WebView の構成、触覚の Tier、精度要件
- 担当タスクの `docs/tasks/Txx-*.md`
- `legacy-unity/`: 旧 Unity（C#）実装。**ロジックの移植元として読むだけにし、編集しない**

## 環境
- Windows 11。シェルは PowerShell か Git Bash
- Android プロジェクトは `android/`。Gradle wrapper を使う（`android/gradlew.bat`）
- Android SDK の場所は `%LOCALAPPDATA%\Android\Sdk`（Android Studio の既定）。**まだ入っていない可能性がある**。その場合は `:core:test`（純粋 JVM）だけを実行し、`:app` のビルドは ⏸ として報告する
- システムの JDK は 25。Gradle と AGP が対応していない場合は toolchain を設定するか、Android Studio 同梱の JBR（`C:\Program Files\Android\Android Studio\jbr`）を使う

## コマンド
```bash
cd android
./gradlew :core:test            # 純粋ロジックのテスト（SDK 不要）
./gradlew :app:assembleDebug    # APK のビルド（SDK が必要）
./gradlew :app:installDebug     # 接続した実機へのインストール
```

## ルール
1. **判定ロジックは `core`（Android に依存しない Kotlin）に置き、JUnit でテストする**。`app` は配線に徹する
2. 触覚は `docs/architecture.md` の精度要件を守る。Android API は公式リファレンスで確認してから使い、推測で書かない
3. Web 側はビルド工程なしの ES modules にする。外部ライブラリはバージョンを固定して `assets/web/vendor/` に同梱し、CDN を実行時に読まない
4. ユーザーのモデルと API キーはコミットしない
5. 依存ライブラリは `gradle/libs.versions.toml` で管理する。必要最小限にとどめる
6. タスクの範囲外に手を広げない。必要だと思ったことは最終報告の「提案」に書く
7. **git commit はしない**

## 最終報告のフォーマット（必ずこの形で最後に出力する）
```
## 結果: 完了 / 一部完了 / ブロック
## 変更ファイル
## 実行した検証と結果（コマンドと、成功または失敗）
## 受け入れ基準のチェック（各項目に ✅/❌/⏸ と理由）
## 実機で確認が必要な項目
## 提案・懸念
```
