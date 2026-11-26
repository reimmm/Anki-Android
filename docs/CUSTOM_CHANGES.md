# AnkiDroid AA カスタム変更まとめ (`stable/v2.25.3-auto-advance`)

> [!NOTE]
> Assisted-by: Antigravity

公式安定版 **AnkiDroid `v2.25.3`**（`d9bf072f81`）をベースに、ハンズフリー学習・Auto Advance（自動進行）・音声学習の快適性を高める機能追加および競合バグ修正を行ったカスタムビルド（**AnkiDroid AA**）の変更まとめドキュメントです。

---

## 1. ビルド・パッケージ仕様

| 項目 | 内容 |
| :--- | :--- |
| アプリ表示名 | `AnkiDroid AA` |
| パッケージ名 (`applicationId`) | `com.ichi2.anki.autoadvance`（公式版 `com.ichi2.anki` と同一端末に共存インストール可能） |
| ベースバージョン | `v2.25.3`（`versionName = 2.25.3` / `versionCode = 322503300`） |
| 対象 ABI | `arm64-v8a`（`minSdk = 24`, `targetSdk = 36`） |
| ContentProvider Authority | `com.ichi2.anki.autoadvance.flashcards` |
| DB 読み書き権限 | `com.ichi2.anki.autoadvance.permission.READ_WRITE_DATABASE` |

---

## 2. リポジトリのブランチ構成

| ブランチ | 役割 |
| :--- | :--- |
| `main` | Upstream（`ankidroid/Anki-Android`）同期用のクリーンなデフォルトブランチ |
| `stable/v2.25.3-auto-advance` | `v2.25.3` ベースのカスタム安定版ソースブランチ（全カスタム機能・修正を収録） |
| `apk-download` | ビルド済み `AnkiDroid-AA-arm64-v8a.apk` 配布用ブランチ |
| `fix/auto-advance-legacy` | 旧学習画面（`Reviewer` / `AutomaticAnswer`）の Auto Advance バグ修正切り出しブランチ（PR #1） |
| `fix/auto-advance-new-reviewer` | 新学習画面（`ReviewerViewModel` / `AutoAdvance`）の Auto Advance バグ修正切り出しブランチ（PR #2） |
| `fix/auto-advance-timer` | 新旧両画面の Auto Advance タイマー修正統合検証ブランチ |

---

## 3. 主な変更内容（7領域）

### 3.1 Auto Advance（自動進行）のタイマー・状態遷移バグ修正

新学習画面・旧学習画面の双方で、手動操作やカード状態遷移と自動進行タイマーが競合して意図せずカードがめくれたり次のカードへ進んでしまう問題を修正しました。

- **新学習画面（`AutoAdvance`, `AutoAdvanceSettings`, `ReviewerViewModel`）**
  - `onShowQuestion()` / `onShowAnswer()` の開始時に質問側・回答側の両タイマー Job を確実にキャンセル（`cancelQuestionAndAnswerActionJobs()`）。
  - 設定読み込み完了後に `settings !== currentSettings` を再検証し、カード切り替え後に古いカードの設定でタイマーがスケジュールされる競合を防止。
  - `showAnswerInternal()`, `undo()`, `redo()`, `loadAndPlayMedia()`（音声完了待ち有効時）の開始時に保留中の Auto Advance タイマーをキャンセル。
  - `deleteNote()`, `buryCard()`, `buryNote()`, `suspendCard()`, `suspendNote()` の `undoableOp` に `handler = this` を渡し、`opExecuted` 側の重複 `updateCurrentCard()` 呼び出しを抑制しつつ `actionsMutex` で直列化。
  - `waitForAudio` を `config.waitForAudio && config.autoplay` として評価し、「音声の自動再生が OFF なのに音声完了待ちでタイマーが動かなくなる」状態を解消。
- **旧学習画面（`AutomaticAnswer`, `AbstractFlashcardViewer`, `Reviewer`）**
  - `delayedShowQuestion()` / `delayedShowAnswer()` で `postDelayed` 前に既存コールバックを除去し、`onDisplayQuestion()` / `onDisplayAnswer()` で両方向のタスクを停止。
  - `undo()` / `redo()` / `bury*()` / `suspend*()` 実行前にタイマー停止と `stopCardMediaPlayer()` を実行。
  - `mediaCompletionGeneration` カウンタを導入し、前カードや別状態の遅延 `onMediaGroupCompleted()` コールバックが現在のカードのタイマーを起動しないよう保護。
  - `automaticAnswerShouldWaitForMedia()` で `cardMediaPlayer.config?.autoplay == true` を必須化。

---

### 3.2 メディア再再生と Auto Advance の連動強化・裏面メディア単体再生

音声付きカードの学習中に聞き直しや自動進行の ON/OFF を行った際の挙動を改善し、裏面音声のみを聞き直せる専用アクションを追加しました。

- **メディア再再生時の Auto Advance タイマーリセット（`ReviewerViewModel`, `AbstractFlashcardViewer`, `AutomaticAnswer`）**
  - `Replay media`（`PLAY_MEDIA`）実行時、`waitForAudio` が有効なら進行中のタイマーを一旦キャンセルして**再再生完了後にタイマーを再開**し、無効なら現在面のタイマーを最初からカウントし直します。
- **「回答のメディアを再再生（`Replay answer media`）」アクション追加（`ViewerAction.PLAY_ANSWER_MEDIA`, `ViewerCommand.PLAY_ANSWER_MEDIA`）**
  - 裏面（解答面）を表示中、質問面＋解答面の全メディアではなく**解答面のメディアのみ**を再再生できるコントロール割り当てを追加（表面表示中に実行した場合は通常のメディア再再生として動作）。
  - ジェスチャー・キー・ゲームパッド・アプリバーボタンに割り当て可能（日英ローカライズ対応）。
- **Auto Advance 無効化時のメディア即時停止（`ReviewerViewModel`, `Reviewer`, `CardMediaPlayer`）**
  - 自動進行を OFF に切り替えた瞬間、再生中のカード音声および TTS を即座に停止するよう変更。
  - `CardMediaPlayer.isPlaying` を `playAvTagsJob != null` から `playAvTagsJob?.isActive == true` に修正し、再生完了後の状態判定を適正化。

---

### 3.3 学習画面のサウンドフィードバック（効果音）と Good ミュート設定

画面を見ずに学習する際にも操作結果が直感的に分かるよう、新学習画面に低遅延の効果音フィードバックを実装しました。

- **`SoundPool` ベースの専用サウンドフィードバック（`ReviewerSoundFeedback`, `ReviewerFragment`）**
  - `AudioAttributes.USAGE_MEDIA` / `CONTENT_TYPE_MUSIC` を使用し、カード音声や TTS と同じメディア音量ストリームで再生。
  - 聴覚疲労を抑えたマリンバ／ソフトチャイム系 WAV アセット（`res/raw/sound_feedback_*.wav`）を収録し、再生ごとに微細なピッチ揺らぎ（`0.994x`〜`1.006x`）を付与：
    - `Again`: 落ち着いた下降ダブルタップ音（ミス・再学習の明確な識別）
    - `Hard`: 控えめなシングルアップタップ音
    - `Good`: 明るい完全五度上昇チャイム
    - `Easy`: 軽やかな3音オクターブ上昇シマー
    - `Auto Advance ON / OFF`: カード回答音と区別しやすい `Fadd9` 上昇／下降シンセチャイム
- **「Good の効果音を鳴らさない」設定スイッチ（`Prefs.soundFeedbackMuteGood`）**
  - `設定 > 学習画面`（`Settings > Study screen`）に追加（デフォルト: **OFF**）。
  - ON にすると最も回答頻度の高い `Good` の効果音のみをミュートし、`Again` / `Hard` / `Easy` および `Auto Advance ON/OFF` 切り替え時のみ効果音を鳴らします。

---

### 3.4 外部連携（Tasker 等）用 Auto Advance トグル Broadcast Intent

イヤホンボタン・メディアボタン・自動化アプリ（Tasker / MacroDroid 等）から、画面遷移なしで学習中の Auto Advance を切り替えられる Broadcast Receiver を実装しました。

- **仕様（`IntentHandler.ACTION_TOGGLE_AUTO_ADVANCE`, `ReviewerFragment`, `Reviewer`）**
  - Action: `com.ichi2.anki.TOGGLE_AUTO_ADVANCE`（`Intent.CATEGORY_DEFAULT` 対応）
  - 新学習画面・旧学習画面それぞれの `onStart()`〜`onStop()` のライフサイクル中のみ動的に登録されるため、**実際に学習画面を開いている間だけ反応**し、デッキ一覧など他画面やバックグラウンド時には誤作動しません。
- **呼び出し例**
  - **ADB コマンド**:
    ```bash
    adb shell am broadcast -a com.ichi2.anki.TOGGLE_AUTO_ADVANCE -p com.ichi2.anki.autoadvance
    ```
  - **Tasker（Send Intent アクション）**:
    - Action: `com.ichi2.anki.TOGGLE_AUTO_ADVANCE`
    - Cat: `Default`（または `None`）
    - Package: `com.ichi2.anki.autoadvance`
    - Target: `Broadcast Receiver`

---

### 3.5 学習中・プレビュー中の BGM 連続ダッキング（音量低下維持）

他アプリで音楽・環境音を流しながら学習する際、カードの音声タグ再生のたびに BGM の音量が毎回下がったり戻ったりする（ポンピング現象）のを防ぐ機能を追加しました。

- **連続オーディオフォーカス保持（`CardAudioFocusManager`, `SoundTagPlayer`, `AbstractFlashcardViewer`, `CardViewerFragment`）**
  - 学習画面またはプレビュー画面がフォアグラウンドにある間、`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` を継続保持し、カード音声の再生完了後も画面を離れるまで他アプリの BGM を一定の控えめな音量に保ちます。
  - 画面を離れた際（`onPause` / `onStop`）や設定 OFF 時には速やかに AudioFocus を解放します。
- **設定スイッチ（`Prefs.duckBackgroundAudio`）**
  - `設定 > 復習`（`Settings > Reviewing`）に **「学習中は背景音楽の音量を下げ続ける」**（`Duck background music while studying`）を追加（デフォルト: **ON**）。

---

### 3.6 共存インストール時の `CardContentProvider` 権限・Authority 修正

`applicationId` を変更したカスタムビルド（`com.ichi2.anki.autoadvance`）を公式版 AnkiDroid（`com.ichi2.anki`）と共存させた際の Provider 競合・権限判定不整合を修正しました。

- **`CardContentProvider` の動的 Authority / Permission 化**
  - `FlashCardsContract.AUTHORITY`（`com.ichi2.anki.flashcards` 固定）のハードコード参照を `${BuildConfig.APPLICATION_ID}.flashcards` および `${BuildConfig.APPLICATION_ID}.permission.READ_WRITE_DATABASE` に置き換え、マニフェスト宣言と実行時の URI マッチング・権限検証が常に一致するよう修正しました。

---

### 3.7 Kiosk モード（任意 QR ロック／解除 & 学習カード数に応じた自由時間）

ADB の Device Owner（`LockTask`）権限を利用して、端末で **AnkiDroid AA のみ** を起動する Kiosk モードを実装しました。音量ボタンと電源ボタン（スリープ／電源メニュー）はロック中もそのまま使用できます。

- **任意の QR コードによるロック／解除（`KioskQrScannerActivity`, `KioskQrDecoder`, `KioskManager`）**
  - CameraX + ZXing によるオフライン内蔵 QR スキャナーを搭載（背面／前面カメラ切替・ライト・暗所／反転 QR 対応）。
  - アンロック状態で任意の QR コードをスキャンして鍵として登録（SHA-256 ハッシュのみ保存）し、同じ QR コードでロックおよび解除を行います。
- **ADB Device Owner による強固な Kiosk 制御（`KioskDeviceAdminReceiver`, `KioskHomeActivity`, `KioskBootReceiver`, `AnkiActivity`, `ManageSpaceActivity`）**
  - ロック中は `setLockTaskPackages` + `setLockTaskFeatures(LOCK_TASK_FEATURE_GLOBAL_ACTIONS or LOCK_TASK_FEATURE_SYSTEM_INFO)` を適用し、ホーム／最近使ったアプリ／通知シェード／外部リンク・共有を遮断しつつ、**音量ボタンと電源ボタンは使用可能**に維持します。
  - `KioskHomeActivity` を永続ホーム（`CATEGORY_HOME`）に設定し、端末再起動後も自動で AnkiDroid に復帰して再ロックします。
  - Device Owner 初期設定コマンド（1回のみ）：
    ```bash
    adb shell dpm set-device-owner com.ichi2.anki.autoadvance/com.ichi2.anki.kiosk.KioskDeviceAdminReceiver
    ```
- **学習カード数に応じた他アプリ利用（自由時間）機能（`KioskManager`, `KioskBreakTimerService`, `KioskStatusDialogFragment`, `Undo.kt`）**
  - Kiosk ロック中の回答カード数を集計し、一定数ごとに他アプリを使える自由時間（休憩タイム）を付与します（デフォルト：**100問ごとに10分**）。
  - Undo（取り消し）による水増しを防止する単調増加カウンタ（`rewarded_units`）と `revlog` 差分補正を実装。
  - 自由時間中は `KioskBreakTimerService` が残り時間を通知バーでカウントダウンし、「学習に戻る」ボタンで残り時間を温存して早期再ロック可能。時間切れになると自動で AnkiDroid を最前面に戻して再ロックします。
- **ビルド前カスタマイズ（`gradle.properties` / Gradle プロパティ）**
  - `gradle.properties` の `kioskRewardCards=100` / `kioskRewardMinutes=10`、またはビルド時引数 `-PkioskRewardCards=50 -PkioskRewardMinutes=5` で `BuildConfig.KIOSK_REWARD_CARDS` / `BuildConfig.KIOSK_REWARD_MINUTES` を自由に変更できます。

---

## 4. 追加された設定・コントロール一覧

| 種別 | 場所 | 名称（日本語 / 英語） | Preference Key / ID | デフォルト |
| :--- | :--- | :--- | :--- | :--- |
| 設定スイッチ | `設定 > 学習画面` | Good の効果音を鳴らさない / `Mute Good answer sound` | `soundFeedbackMuteGood` | `false` |
| 設定スイッチ | `設定 > 復習` | 学習中は背景音楽の音量を下げ続ける / `Duck background music while studying` | `duckBackgroundAudio` | `true` |
| コントロール | `設定 > コントロール` | 回答のメディアを再再生 / `Replay answer media` | `binding_PLAY_ANSWER_MEDIA` | 割り当てなし |
| メニュー | デッキ一覧 / 学習画面メニュー | Kiosk モード / `Kiosk mode` | `R.id.action_kiosk_mode` | — |
| ビルド設定 | `gradle.properties` | Kiosk 自由時間の必要カード数 / 付与分数 | `kioskRewardCards` / `kioskRewardMinutes` | `100` / `10` |
| Broadcast | 学習画面表示中のみ | Auto Advance ON/OFF トグル | `com.ichi2.anki.TOGGLE_AUTO_ADVANCE` | — |

---

## 5. コミット履歴（`v2.25.3` 以降）

| Commit | 日付 (UTC) | 概要 |
| :--- | :--- | :--- |
| `fa6cb32` | 2026-11-14 | `fix(reviewer): cancel pending auto-advance timers on state change and undo` |
| `e8b25b6` | 2026-11-14 | `feat(reviewer): add sound feedback for answer and auto-advance toggle` |
| `477ca33` | 2026-11-14 | `fix(reviewer): route feedback tones through media audio stream` |
| `2586750` | 2026-11-14 | `fix(reviewer): fix AudioTrack MODE_STATIC state check before write` |
| `f66eb43` | 2026-11-14 | `feat(reviewer): add broadcast intent to toggle auto advance while studying` |
| `1dc2749` | 2026-11-14 | `feat(reviewer): redesign sound feedback with modern polyphonic chimes` |
| `df625ca` | 2026-11-14 | `feat(reviewer): use soft marimba SoundPool assets and add mute-Good option` |
| `29baf48` | 2026-11-17 | `feat(reviewer): reset auto advance on media replay and add answer-only media action` |
| `d3063de` | 2026-11-18 | `feat(reviewer): stop active media playback when disabling auto advance` |
| `8b23af4` | 2026-11-18 | `test(reviewer): await IO-dispatched settings and media completion in ReviewerViewModelTest` |
| `c62f821` | 2026-11-18 | `test(reviewer): clean up ReviewerViewModelTest replay waits` |
| `3242287` | 2026-11-18 | `test(reviewer): make auto advance media tests deterministic` |
| `2b42253` | 2026-11-18 | `feat(audio): add continuous background music ducking during study and preview` |
| `3ee7dc5` | 2026-11-23 | `fix(provider): use BuildConfig.APPLICATION_ID for CardContentProvider authority and permission` |
