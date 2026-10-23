# T02: センサーと身体言語の入力（Kotlin）

## 前提
- 仕様の本体は **`docs/tasks-unity-legacy/T02-sensing.md`**（Unity 向けに書いた版）。ジェスチャーの定義、閾値の初期値、記録と再生、GestureLab、受け入れ基準はそちらに従う
- `legacy-unity/Assets/VtubePhone/Core/` に、Unity 版で途中まで作った `GestureRecognizer`、`MotionDetectors`、`KnockSequencer`、`GestureArbiter`、`GestureConfig`、`TouchGestureRecognizer`、`MotionCsv` がある。**移植の参考にしてよいが、途中で止めたものなので、未完成や誤りがある前提で読むこと**

## Kotlin 版での読み替え
| Unity 版 | Kotlin 版 |
|---|---|
| `VtubePhone.Core`（C#） | `android/core` の `gesture/` パッケージ（JVM）。テストは `./gradlew :core:test` |
| `MotionSensor.java` + JNI の drain | `app/sensing/MotionSensor.kt`。`SensorManager` を `SENSOR_DELAY_FASTEST` で購読し、専用の `HandlerThread` 上で core の認識器に直接流す（JNI はない） |
| Input System の touch | WebView 上のタッチは JS で `pointerdown`、`move`、`up` を `event.timeStamp` とともに集め、ブリッジ経由で送る。ネイティブ画面では `MotionEvent.eventTime` を使う。どちらも core の `TouchGestureRecognizer` に入れる |
| `Application.persistentDataPath` | `context.getExternalFilesDir("recordings")`。取り出しは `adb pull /sdcard/Android/data/com.vtubephone.app/files/recordings` |
| GestureLab シーン | `GestureLabActivity`（Compose）。メイン画面とベンチから移動できる |

## 追加要件
- `GestureEvent` をアプリ全体に配信する `GestureBus`（Kotlin Flow）を用意する。T04 の脳がこれを購読する
- MainActivity（WebView）にも検出したジェスチャーを送り、画面下に小さく表示する（デバッグ用。設定で消せるようにする）
- 電池への配慮: 画面が OFF のときやアプリがバックグラウンドのときはセンサーを止める
- `back_knock` は端末差が大きい。GestureLab に**感度のキャリブレーション**（背面を 5 回叩いてもらい、その強さから閾値を決めて保存する）を入れる

## 受け入れ基準
1. `./gradlew :core:test` が通る。テストの観点は旧仕様の受け入れ基準 1 に従う
2. `./gradlew :app:assembleDebug` が成功する
