# T01 rev2: 触覚の語彙を広げる（実機フィードバックによる）

## 実機の結果（Galaxy SM-S942Q、Android 16、API 36）
- Best Tier は B。**8 つのプリミティブすべてに対応**。長さは CLICK 20ms、THUD 300ms、SPIN 130ms、QUICK_RISE 150ms、SLOW_RISE 500ms、QUICK_FALL 100ms、TICK 20ms、LOW_TICK 20ms
- エンベロープ（Tier A）には非対応。共振周波数と Q 値は取得できない
- 遅延: 呼び出しから vibrate() が返るまで約 0.5〜2.4ms、JS からネイティブまで約 0.5〜1.3ms。目標を満たしている
- はい／いいえのブラインドテストは識別できた ✅
- **オーナーの感想: 「12 個の振動、結構バリエーションないね」** ← 今回直す問題

## 原因
1. Renderer は transient を TICK、CLICK、LOW_TICK、THUD の 4 つにしか割り当てていない。**SPIN、QUICK_RISE、SLOW_RISE、QUICK_FALL を一度も使っていない**。continuous を含むクリップは、すべて Tier C（振幅の波形）に落ちている
2. クリップの多くが「短い transient の間隔を変えたもの」で、長さ、質感、強弱の変化（ダイナミクス）の差が小さい
3. `thud` は THUD（300ms）の 220ms 後に次のイベントがあり、この端末では重なってしまい、Tier C に落ちる

## 作業内容

### 1. クリップ形式の拡張: `primitive` イベント
```json
{ "t": 0, "type": "primitive", "name": "spin", "scale": 0.8 }
```
- `name` は click、thud、spin、quick_rise、slow_rise、quick_fall、tick、low_tick のいずれか。デザイナーがプリミティブを直接指定できるようにする
- Tier B: そのまま `addPrimitive` にする。時間の計算には端末から得たプリミティブの長さを使う。**重なる場合は、Tier C に落とさず、後ろのイベントを必要最小限だけ遅らせる**。ずらした量は命令列の reason に記録する。ただし knock のようにリズムが意味を持つクリップには `"strictTiming": true` を指定できるようにし、その場合は従来どおり重なったら下の Tier に落とす
- Tier C と D: 各プリミティブを振幅の波形で近似する。spin は振幅を揺らす、rise と fall は傾斜、thud は強い山と減衰、のようにする。近似の定義は表にまとめ、テストで固定する
- 既存の `transient` と `continuous` はそのまま使えるようにする（後方互換）

### 2. 12 個のクリップを作り直し、6 個を追加する
「長さ」「質感」「強弱の変化」「リズム」の 4 軸で、お互いに**はっきり違う**ものにする。以下は案で、細部は任せる。

| ID | 意図 | 構成案 |
|---|---|---|
| `knock` | 内側からノック | low_tick か click を中くらいの強さで。strictTiming |
| `thud` | 画面を叩く | thud 1.0 → （300ms 以上あけて）low_tick 0.4 → low_tick 0.2 |
| `bump` | 衝突 | 衝撃に応じて click から thud へ切り替え、scale も変える |
| `yes` | はい | tick 0.5 → tick 1.0（短く、明るく、上がる） |
| `no` | いいえ | slow_rise 0.4 → quick_fall 0.8（「ううん」と低く沈む） |
| `call` | ねえねえ | click ×3 |
| `purr` | 喉を鳴らす | low_tick を 40〜60ms 間隔で 10〜15 回、scale をゆっくり上下させる（粒状のゴロゴロ） |
| `heartbeat` | ドキドキ | click 0.9 + 120ms 後に low_tick 0.5 を 2〜3 組 |
| `nervous` | 緊張 | tick を 25〜40ms の不規則な間隔で、小さい scale で連打 |
| `giggle` | 笑う | quick_rise を短く挟みつつ、tick の不規則なバースト |
| `dizzy` | 目が回る | spin ×3、だんだん弱く |
| `poke` | つつく | tick 1.0 を 1 回 |
| 追加 `surprise` | びっくり | quick_rise 1.0 → thud |
| 追加 `sigh` | ため息、がっかり | slow_rise 0.3 → quick_fall 0.3 |
| 追加 `excited` | わくわく | click の加速するリズム → spin |
| 追加 `sleepy` | 眠い | slow_rise をとても弱く 2 回、間隔を長めに |
| 追加 `angry` | 怒る | thud → thud の短い連打 |
| 追加 `shy` | 照れ | tick を 2 回、弱く + spin を弱く 1 回 |

- `docs/product.md` の触覚フレーズ表を更新する
- `yes` と `no` は引き続き、触覚だけで区別できること

### 3. ベンチ画面: プリミティブのパレットと簡易シーケンサー
- **パレット**: 8 つのプリミティブを、scale（0.2、0.5、1.0）を選んで単発で鳴らせる。オーナーが端末の「素材」を体験できるようにする
- **シーケンサー**: プリミティブを選んで並べ、間隔と scale を指定して再生できる。作ったものは JSON としてログに出し、クリップボードにコピーする。オーナーが自分で振動をデザインできるようにする
- **Tier の上書きを目立たせる**: 現在の Tier と「この端末の Best Tier」を大きく表示し、Best 以外を選んでいるときは警告色にする（今回、オーナーが Tier C のまま試していた）
- 各クリップのボタンに、この端末で実際に鳴る Tier を表示する

## 受け入れ基準
1. `./gradlew :core:test` がすべて通る。追加するテストは次のとおり
   - primitive イベントのパース
   - 重なりを遅らせて解決すること、strictTiming のときは下の Tier に落ちること
   - Tier C での近似
   - **この端末の実測値（上記のプリミティブの長さ）を使ったモックで、knock 以外の 18 個すべてが Tier B で描画されること**。knock は 1〜4 回とも B で描画されること
2. `./gradlew :app:assembleDebug` が成功する（SDK はインストール済み。compileSdk は 37）
3. 実機での確認は PM とオーナーが行う
