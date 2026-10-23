# 触覚語彙 rev2 の設計・検証

## イベントと時刻

- `primitive`: `t`（開始ms）、`name`、`scale`（0〜1）を指定する。全体の強さはscaleに乗算する。
- 既存の `transient` と `continuous` は変更せず受け付ける。transientのsharpnessからの4素材への割り当ても維持する。
- Tier Bでは対応済みかつ長さが既知のプリミティブのみをCompositionにする。`getPrimitiveDurations` のためAPI 31以上が必要。
- `addPrimitive` の3引数版のdelayは、前のプリミティブの終了からの待ち時間。各開始は `max(指定t, 直前の終了)`。元の時刻に戻れる後続イベントまで一律に後ろへずらさない。
- ずれた各イベントの添字と移動量、元・実際の時刻を `reason` に出す。`strictTiming: true` のときは重なりを解決せずC/Dへ落とす。省略時はfalse。
- 対応なし・長さ不明・continuousとの混在・調整後に1分超過の場合も、全体を1本の波形にする。タイマーで分割しない。

Android公式: [Composition.addPrimitive](https://developer.android.com/reference/android/os/VibrationEffect.Composition#addPrimitive(int,float,int))、[Vibrator.getPrimitiveDurations](https://developer.android.com/reference/android/os/Vibrator#getPrimitiveDurations(int...))。

## Tier C/D の固定近似表

これは波形による質感の近似であり、端末の実際の周波数・モーター出力の測定値ではない。制御点間を線形補間し、ローカル時刻を5ms単位で切り捨ててサンプルする。終了時刻は含まない。Cでは `scale × 全体の強さ × 下記振幅` を0〜255に丸める（ties-to-even、正の値は最低1）。既存イベントと重なるときは最大振幅を採用する。Dでは同じ値を1msのpulse-density PWMに変換し、ON/OFF波形1本として再生する。

| name / Android ID | 近似の長さ | 制御点 `(ms, 振幅0〜1)` | 質感 |
|---|---:|---|---|
| click / 1 | 20ms | (0,1), (5,0.75), (20,0) | 短いクリック |
| thud / 2 | 300ms | (0,1), (20,0.8), (80,0.35), (300,0) | 強い山と長い減衰 |
| spin / 3 | 130ms | (0,0.15), (30,0.85), (60,0.2), (90,1), (130,0) | 回転を模した強弱2山 |
| quick_rise / 4 | 150ms | (0,0), (150,1) | 速く立ち上がる |
| slow_rise / 5 | 500ms | (0,0), (500,1) | ゆっくり立ち上がる |
| quick_fall / 6 | 100ms | (0,1), (100,0) | 沈んでいく |
| tick / 7 | 20ms | (0,1), (5,0.35), (20,0) | 鋭く、すぐ弱まる |
| low_tick / 8 | 20ms | (0,0.55), (10,0.4), (20,0) | 弱く丸い粒 |

Tier Bの長さにはこの表を使わず、端末が返す値を使う。`HapticVocabularyTests` が表の全5msサンプル、先頭の無振動、scale、全体の強さ、PWMの積算ON時間を固定する。

## 18クリップと実測モック

`docs/product.md` の18種類をすべてprimitiveのみで定義する。長さ（20ms〜2.1秒）、質感（8素材）、ダイナミクス（加速・減衰・うねり）、リズム（2拍・3連・不規則）の違いを設ける。`bump` のJSONは衝撃0.5の標準サンプルで、実際の衝突再生はfactoryが入力0〜1をscaleに反映し、0.6以上でclickからthudに切り替える。

Galaxy SM-S942Qの報告値をモックに使用する: CLICK 20ms、THUD 300ms、SPIN 130ms、QUICK_RISE 150ms、SLOW_RISE 500ms、QUICK_FALL 100ms、TICK / LOW_TICK 20ms、エンベロープ非対応。全18種とknockの1〜4回が、開始時刻を変えずTier Bで描画されることを検証する。仕様の「knock以外の18個」は表の合計と一致しないため、表の全18種を対象にする。

`yes` は上がる短いtick 2発（130ms）、`no` は長いrise→fall（600ms）で区別を設計する。JUnitは構造・時刻の違いを検証し、知覚による識別と18種類の体感差はPM・オーナーが実機で再確認する。

## ベンチ

- 現在の選択TierとBest Tierを大きく表示し、上書き中は警告色と復帰ボタンを表示する。個別ボタンはネイティブ構築時のフォールバックも反映したTierを表示する。
- パレット: 8素材をscale 0.2 / 0.5 / 1.0で選択。未対応素材はC/Dで近似する。全組合せを事前に構築・キャッシュする。
- シーケンサー: 最大32段。素材・scale・前の振動終了からの間隔（UIは0〜2000ms）を段ごとに変更、追加・削除・並べ替えできる。全体は最大1分。最初の間隔は開始待ち。
- 長さが既知なら端末値、不明なら上表の近似長を使って絶対時刻 `t` を生成する。JSONは同じ時刻・元のscaleを持ち、通常のクリップパーサーで読み込める。再生時・コピー時にログへ出し、コピーは [ClipboardManager.setPrimaryClip](https://developer.android.com/reference/android/content/ClipboardManager#setPrimaryClip(android.content.ClipData)) を使う。
- 編集時に再構築し、再生時は準備済みエフェクトを1回呼ぶ。既存のOFF・強さ・回数制限・バックグラウンド停止を経由する。ブラインドテスト中はパレットと編集画面を隠す。
