# Repository Guidelines (日本語版・サマリ)

このドキュメントは、GeoLocationProvider リポジトリで作業する際の **方針・レイヤ構造・責務の分担** を簡潔にまとめたものです。  
詳細な仕様は英語版 `AGENTS.md` を正とし、本書はそのダイジェストとして扱ってください。

---

## モジュール構成と依存関係

- ルート Gradle プロジェクト `GeoLocationProvider` は、主に次のモジュールから構成されます。
  - `:app` – Jetpack Compose ベースのサンプル UI（履歴・Pickup・Drive 設定・手動エクスポート・Map 画面など）。
  - `:core` – 位置取得サービス `GeoLocationService` とセンサー処理。永続化は `:storageservice` に委譲し、DR は `:deadreckoning` を利用。
  - `:storageservice` – Room `AppDatabase` / DAO と `StorageService` ファサード。位置ログとエクスポート状態の唯一の入口。
  - `:dataselector` – Pickup 用の選択ロジック。`LocationSample` を条件でフィルタし、代表サンプル行 `SelectedSlot` を構築。
  - `:datamanager` – GeoJSON エクスポート、ZIP 圧縮、`MidnightExportWorker` / `MidnightExportScheduler`、Google Drive 連携。
  - `:deadreckoning` – Dead Reckoning エンジンと公開 API（`DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`）。
  - `:auth-appauth` / `:auth-credentialmanager` – `GoogleDriveTokenProvider` の実装を提供する認証モジュール。

- 依存関係の向き（概要）
  - `:app` → `:core`, `:dataselector`, `:datamanager`, `:storageservice`, auth モジュール
  - `:core` → `:storageservice`, `:deadreckoning`
  - `:datamanager` → `:storageservice`, Drive 連携クラス
  - `:dataselector` → `LocationSampleSource` 抽象のみ（実装は `:app` 側で `StorageService` をラップ）

---

## Dead Reckoning モジュールと GeoLocationService

### DeadReckoningConfig

`DeadReckoningConfig` は DR エンジンの挙動を制御する設定オブジェクトです。主なパラメータ:

- `staticAccelVarThreshold` – 静止判定に使う加速度分散のしきい値。
- `staticGyroVarThreshold` – 静止判定に使うジャイロ分散のしきい値。
- `processNoisePos` – 位置のプロセスノイズ（ドリフトの増え方）。
- `velocityGain` – GPS 速度と内部速度推定のブレンド係数。
- `maxStepSpeedMps` – 1 ステップあたりの物理的な最大速度 [m/s]。  
  これを超えるステップは IMU スパイクとみなされ、エンジン側で破棄されます。0 以下を指定すると無効。
- `debugLogging` – DR エンジン内部のデバッグログを有効にするフラグ（通常は false）。
- `windowSize` – 静止判定用の移動窓のサイズ。

### DeadReckoning エンジンの役割

- IMU（加速度 / ジャイロ / 磁気）入力から、
  - heading（方位）、
  - 水平速度、
  - 緯度・経度
  を逐次推定します。
- 静止判定（ZUPT 風の判定）
  - 直近の加速度ノルム・ジャイロノルムの分散がしきい値以下のとき `isLikelyStatic() == true`。
  - 静止とみなした場合は速度を漸減させ（例: `speedMps *= 0.85f`）、ランダムウォークを抑制します。
- 物理速度ガード
  - 1 センサステップごとに移動距離とステップ速度を計算し、`stepSpeedMps > maxStepSpeedMps` の場合はそのステップを **破棄** します（状態は更新しない）。
  - `debugLogging == true` のときのみ `DR/SANITY` ログを出力します。
- GPS フィックス
  - `submitGpsFix()` 呼び出しごとに、内部状態の `lat/lon` を **GPS の値で再アンカー** します。
  - 速度は `velocityGain` に従って GPS 速度へ平滑に寄せます。
  - 位置の不確かさ `sigma2Pos` は、GPS 精度から再初期化されます。
- 予測 `predict(fromMillis, toMillis)`
  - 内部状態のスナップショットを返す API であり、このメソッド自体は追加の積分は行いません（積分は `onSensor()` 側）。
  - **初回 GPS フィックス前** は絶対位置が未定のため、空リストを返す場合があります。

### GeoLocationService 側の責務

- DR インスタンスの生成とライフサイクル管理
  - `DeadReckoningFactory.create(applicationContext)` でインスタンスを生成し、サービスの `onCreate` で `start()`、`onDestroy` で `stop()`。
- GPS 更新時
  - DB に `provider="gps"` の `LocationSample` を挿入。
  - 同じタイムスタンプで `GpsFix` を組み立て、`dr.submitGpsFix()` に渡して DR 側のアンカーを更新。
  - 最新 GPS 位置を「DR クランプ用アンカー」として保持（Map 表示などにも利用）。
- DR サンプル生成時
  - `DeadReckoning.predict()` から `PredictedPoint` を受け取り、`provider="dead_reckoning"` の `LocationSample` として DB に挿入。
  - `dr.isLikelyStatic() == true` の場合、最新 GPS との距離が一定以上（約 2m）に離れていれば、アンカー位置にクランプすることでデスク上などでのドリフトを抑制。
- これらのロジックは `:deadreckoning` の API 外側にあり、DR の公開インターフェース自体はシンプルに保ちます。

---

## Map 画面（Polyline 表示）のガイド

- `MapScreen` / `GoogleMapsExample` は MapConductor（`GoogleMapsView` + Google Maps backend）を利用してマップを表示します。
- UI 上部には、左から
  - `[ ] GPS` チェックボックス
  - `[ ] DeadReckoning` チェックボックス
  - `Count (1-1000)` テキストフィールド
  - `Apply` / `Cancel` ボタン
  が横並びで配置され、垂直方向にセンタリングされています。
- 初回表示時
  - チェックボックスはすべて未チェックで、マップ上にはポリラインが表示されません。
- `Apply` ボタン押下時の挙動
  - `GPS` / `DeadReckoning` チェックボックスと `Count` フィールドをロック（編集不可）にし、ボタンラベルを `Cancel` に変更。
  - DB の最新 `LocationSample` から、チェックされたプロバイダのみを対象に、`Count` を上限とする件数を取得（GPS + DR の合計が Count を超えないように制限）。
  - 取得したサンプルを `timeMillis` 昇順（古い順）に並べ、
    - DeadReckoning: 赤色・細めのポリラインとして **後から描画**（前面）。
    - GPS: 青色・太めのポリラインとして **先に描画**（背面）。
  - 新しいサンプルが保存された場合、表示中の状態を保ったまま末尾に追加し、総数が `Count` を超えた場合は古いものから削除。
- `Cancel` ボタン押下時の挙動
  - マップ上のポリラインをすべて削除。
  - チェックボックスと `Count` フィールドをアンロックし、ボタンラベルを `Apply` に戻す。
  - このとき、マップのカメラ位置・ズームは変更しません（ユーザーが調整した視点は維持されます）。
- デバッグオーバーレイ
  - 画面右上に半透明のパネルを表示し、
    - `GPS : 表示件数 / DB 件数`
    - `DR  : 表示件数 / DB 件数`
    - `ALL : 表示総数 / DB 総数`
    を表示して簡易的な整合性チェックができるようにしています。

---

## 注意事項（共通）

- 実装や設計を変更する際は、英語版 `AGENTS.md` とこの日本語サマリの両方を更新し、
  - レイヤ境界（`:deadreckoning` / `:core` / `:app`）を越える責務が混ざらないか、
  - Map 表示仕様（Polyline / Apply/Cancel / Count / debug overlay）との整合性
  を意識してください。
- 本ファイルはドキュメント専用のため、日本語（マルチバイト文字）の使用を許可しますが、**コード・コメント・文字列リテラル** は ASCII のみとしてください（`AGENTS.md` の方針に従うこと）。

