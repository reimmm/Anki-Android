# AnkiDroid AA (`com.ichi2.anki.autoadvance`) — APK Download

> [!NOTE]
> Assisted-by: Antigravity

公式安定版 **AnkiDroid `v2.25.3`** をベースに、Auto Advance（自動進行）・音声再生・効果音・BGM 連続ダッキング・Tasker 外部連携・**Kiosk モード（任意 QR ロック／解除 & 学習カード数に応じた自由時間）** を強化したカスタムビルド **AnkiDroid AA** の APK 配布ブランチです。

## ダウンロード

- **GitHub Releases（推奨）**: [`v2.25.3-aa.2`](https://github.com/reimmm/Anki-Android/releases/tag/v2.25.3-aa.2)
- **このブランチの最新 APK**: [`AnkiDroid-AA-arm64-v8a.apk`](./AnkiDroid-AA-arm64-v8a.apk)

| 項目 | 値 |
| :--- | :--- |
| ファイル名 | `AnkiDroid-AA-arm64-v8a.apk` |
| アプリ名 / パッケージ名 | `AnkiDroid AA` / `com.ichi2.anki.autoadvance`（公式版と共存可） |
| ベース / バージョン | `v2.25.3`（`versionCode = 322503300`） |
| ソースコミット | [`de1e1ad479`](https://github.com/reimmm/Anki-Android/commit/de1e1ad479)（[`stable/v2.25.3-auto-advance`](https://github.com/reimmm/Anki-Android/tree/stable/v2.25.3-auto-advance)） |
| 対応 ABI / SDK | `arm64-v8a`（Android 7.0 `minSdk 24` 〜 Android 16 `targetSdk 36`） |
| サイズ | `41,799,691 bytes`（約 39.9 MB） |
| SHA-256 | `6d2848957138e1c4c1ed077981ca1b0aab0ea03c9f3170833d3d530441a1cdf0` |

---

## 主な追加機能・修正ハイライト

1. **Kiosk モード（任意 QR ロック／解除 & 学習カード数に応じた自由時間）**
   - ADB の Device Owner 権限を用いて端末で AnkiDroid AA のみを起動する専用 Kiosk モードを搭載（音量ボタン・電源ボタンはロック中もそのまま使用可能）。
   - 任意の QR コードをスキャンして鍵として登録し、同じ QR コードでロック／解除（オフライン内蔵カメラ QR スキャナー）。
   - 学習カード数に応じて他アプリを使える自由時間を獲得（デフォルト **100問 = 10分**、`gradle.properties` の `kioskRewardCards` / `kioskRewardMinutes` またはビルド時 `-P` オプションで変更可能）。
   - Device Owner 初期設定コマンド（1回のみ）：
     ```bash
     adb shell dpm set-device-owner com.ichi2.anki.autoadvance/com.ichi2.anki.kiosk.KioskDeviceAdminReceiver
     ```
2. **Auto Advance タイマー・状態遷移の競合バグ修正（新旧学習画面両対応）**
   - Undo / Redo / Bury / Suspend / Delete / 解答表示時に古いタイマーや前カードの遅延メディア完了通知が残って勝手に進行する不具合を解消。
   - 音声自動再生が OFF の場合に `waitForAudio` でタイマーが停止してしまう問題を修正。
3. **メディア再再生連動 & 「回答のメディアを再再生」アクション追加**
   - メディア再再生時に Auto Advance タイマーをリセット（`waitForAudio` 有効時は再再生完了後に再スケジュール）。
   - 裏面表示中に裏面メディアのみを聞き直せるコントロール **「回答のメディアを再再生（`Replay answer media`）」** を追加。
   - Auto Advance を OFF にした瞬間に再生中のカード音声・TTS を即時停止。
4. **ソフトマリンバ効果音フィードバック & Good ミュート設定**
   - 新学習画面で `Again` / `Hard` / `Good` / `Easy` 回答時および `Auto Advance ON/OFF` 切替時に低遅延チャイムを再生（メディア音量連動）。
   - `設定 > 学習画面 > Good の効果音を鳴らさない` で `Good` 回答時のみ無音化可能。
5. **Tasker 等の外部連携用 Broadcast Intent**
   - 学習画面を開いている間のみ応答するトグル用 Broadcast を搭載：
     - Action: `com.ichi2.anki.TOGGLE_AUTO_ADVANCE`
     - Package: `com.ichi2.anki.autoadvance`
     - Target: `Broadcast Receiver`
6. **学習中・プレビュー中の BGM 連続ダッキング**
   - `設定 > 復習 > 学習中は背景音楽の音量を下げ続ける`（デフォルト ON）により、カード音声の合間に他アプリの音楽音量が上下するポンピング現象を防止。
7. **公式版との共存インストール完全対応**
   - `CardContentProvider` の Authority / Permission を `BuildConfig.APPLICATION_ID` ベースに動的化し、公式版 AnkiDroid（`com.ichi2.anki`）と競合なく同居可能。

---

## 詳細ドキュメント

全変更の技術仕様・追加設定キー・コミット履歴の詳細は以下を参照してください：

- [`docs/CUSTOM_CHANGES.md` (`stable/v2.25.3-auto-advance`)](https://github.com/reimmm/Anki-Android/blob/stable/v2.25.3-auto-advance/docs/CUSTOM_CHANGES.md)
