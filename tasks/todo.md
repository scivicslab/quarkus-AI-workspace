# 会話ログの横断検索

## 調査（完了）

- [x] 会話ログのデータベースの実測（47ファイル、58.6 MiB、1日あたり0.8 MiB）
- [x] `chat-ui-iolog-<ポート番号>.mv.db` のスキーマ確認（`sessions` / `logs` / `node_results`）
- [x] 1会話ターンが `logs` テーブルの1行として書かれることの確認
- [x] `log-merge` の統合機能の確認（`source_db` 列の付加、重複判定の基準）
- [x] `log-search` に本文キーワード検索が無いことの確認
- [x] `db-clear` が稼働中のデータベースを判定できないことの確認
- [x] 本文キーワード検索が `IoLogView.logs()` の中だけにあることの確認
- [x] 4つの CLI サブコマンドを実際に起動して確認（`~/.turing-workflow/plugins.yaml` が無いため未登録、
      登録しても H2 のドライバが無く全接続が失敗、`--scan ~/works` が会話ログ以外を55個拾う）

## 設計文書（完了）

- [x] `ConversationLogSearchPlacement_260908_oo01` を
      `doc_SCIVICS002/docs/quarkus-AI-workspace/040_design` に置く

## 実装（未着手・設計文書の承認待ち）

- [x] `~/.turing-workflow/plugins.yaml` を作り `plugin-log-db` と `com.h2database:h2` を登録する
      （H2 の同梱は不要だった。ホストは1エントリにつき1つの jar しか解決しないため、
      H2 を別エントリとして並べれば同じクラスローダに載る）
- [x] `MergeLogsCLI.openDatabase` の JDBC URL に `AUTO_SERVER=TRUE` を足す
- [x] `log-merge` に `--name-prefix` を足し、走査対象をファイル名で絞る
- [x] `log-merge` の統合先を `COMPRESS=TRUE` で開く（79 MB → 35 MB）
- [x] `log-merge` が `cwd` / `command_line` / `plugin_version` 等6列を写すようにする
- [x] `IoLogStore` が呼ぶ `startSession` を10引数の形に変える（`quarkus-chat-ui` と
      `chat-ui-with-audit-trail` の両方）
- [x] `quarkus-AI-workspace` に Conversations 画面を足す（`ConversationLogSearch` と
      `ConversationResource` と `conversations.html`、`layout.html` にタブを追加）
- [x] `assetVersion` を `AssetVersion` bean に切り出し、画面をまたいで同じ値にする
- [x] `ConversationLogSearchTest` を書く（9件 GREEN）
- [x] 集約の実装を `LogMerger` として `plugin-log-db` に切り出し、`MergeLogsCLI` を委譲に変える
- [x] `quarkus-AI-workspace` が `LogMerger` を直接呼ぶ（`ConversationLogMerge` と
      `ConversationLogMergeActor`、画面の Collect new conversations ボタン）
- [x] 本番の `quarkus-AI-workspace`（ポート28000）を新しい jar で再起動し、初回の集約を実行
- [x] 5つのリポジトリを push する（`quarkus-chat-ui` と `chat-ui-with-audit-trail` は
      別セッションが先に push 済みだった）
- [x] `quarkus-chat-ui` と `chat-ui-with-audit-trail` を Build Snapshot で入れ替える
      （`~/works` の jar は新しい。稼働中のインスタンスは古い jar のまま動いている）
- [x] 稼働中の会話インスタンス7つを全て新しい jar で再起動する
- [ ] `chat-ui-with-audit-trail` の Activity が旧いセッション名を見つけられない問題
      （ポート28012。`findResumableSession` は `chat-ui-conversation-<タブ識別子>` だけを探すが、
      このデータベースには `chat-ui-conversation` という旧名のセッションしかない）
