# T01 rev1: PM レビューによる修正指示

T01 の実装はおおむね良好です。Java のリフレクションで使った名前は、API 36 の android.jar で PM が javap を使って確認済みです。以下の 4 点を修正してください。

## 1. VibrationAttributes（重要。PM の仕様ミスによるもの）
- `USAGE_TOUCH` をやめて **`VibrationAttributes.USAGE_MEDIA`** にする。API 33 未満のフォールバックは `AudioAttributes.USAGE_GAME` にする
- `refreshSettings()` で `Settings.System.HAPTIC_FEEDBACK_ENABLED` を見て全体を無効にしている処理を**削除する**。アプリ内の OFF（RateLimiter.Enabled）だけでミュートする
- 理由は `docs/architecture.md` の精度要件 6 を参照（更新済み）
- 能力レポートに、使用している usage と、取得できるなら端末の media 振動の強さに関する情報を追加する（取れなければ省略してよい）

## 2. `thud` を Tier B で鳴らせるようにする
- 現状は transient と continuous が混在しているため、Tier A や B の端末でも C に落ちている。`thud` は仕返しのクライマックスなので、Tier B で鳴ることが重要
- `thud.json` を **transient だけ**で作り直す。最初に強く鈍い 1 発（THUD 相当）、続いて減衰する 2〜3 発の余韻（LOW_TICK 相当、intensity を下げていく）にする。間隔は、代表的な端末のプリミティブの長さ（THUD は数十〜百数十 ms）と重ならないよう余裕を取る
- テストを追加する: プリミティブの長さを現実的な値にしたモックの capabilities で、`thud` と `knock`（1〜4）と `yes` と `call` が Tier B で描画されること（C に落ちないこと）

## 3. ドキュメントとコマンド
- `AGENTS.md` の「コマンド」に `dotnet test tools/CoreTests/CoreTests.csproj` を**高速パス**として追記する（Unity なしで Core のテストを実行できる）
- `docs/haptics-engine.md` に、Tier ごとにどのクリップがどの Tier で鳴るかの表を追加する（Tier A、B、C の端末それぞれについて）

## 4. 検証
- `dotnet test tools/CoreTests/CoreTests.csproj` がすべて通ること
- `tools/verify-static.ps1`（javac で API 31 と API 36 に対してコンパイル）が通ること
- Unity のライセンスは引き続き未有効化の見込み。Unity のコマンドは失敗したらスキップしてよい
