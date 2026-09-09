# Conversations 検索結果から前後の turn へ移動する

## 現状

`/conversations` は `logs` の `message` を部分一致で検索し、1 行を 1 件のヒットとして返す。
ヒットを開くと `GET /conversations/turn/{logId}` がその行の本文だけをプレーンテキストで返す。
そこから前後へ移動する手段がない。

## データの前提（マージ済み DB を実測して確認）

- `logs` は `id`(BIGINT)、`session_id`、`timestamp`、`label`、`message` を持つ。
- `LogMerger.copyLogs` が 1 会話分の行を連続挿入するため、同一 `session_id` 内では `id` 昇順が
  時系列順になる。全行検査で `id` 順に対し `timestamp` が逆行する行は 0 件。
- `label` は全行が `turnN/stepM/{llm|tool}` の形。空・NULL は 0 件。
- 1 つの turn が複数行にまたがる（`turn1/step1/llm`, `turn1/step1/tool`, `turn1/step2/llm` …）。
- turn 番号には欠番がある（例: turn6 の次が turn8）。よって移動は番号の増減ではなく
  `id` 順で次に現れる別 turn として求める。

## 設計

既定は turn 単位。パネルにはその turn に属する全行を `id` 順で並べ、各行に `label` を見出しとして付ける。
← / → はその会話の中で前後の turn へ移動する。行単位モードも用意し、トグルで切り替える。
モードを切り替えても、いま見ている行を軸に位置を保つ。

前後の turn は、現在の turn に属する行の `id` の最小・最大を基準に求める。
turn キーが会話の中で非連続に現れても正しく動く。

- 次の turn: `session_id` が同じで `id > (現 turn の最大 id)` の最初の行が属する turn
- 前の turn: `session_id` が同じで `id < (現 turn の最小 id)` の最後の行が属する turn
- 次の行 / 前の行: 同様に `id` の直後・直前の行

## 手順

- [x] 1. `ConversationLogSearch` に turn / 行の取得と前後の解決を追加する
      (`turnAt(logId)`, `rowAt(logId)`, それぞれの prev/next)
- [x] 2. `ConversationLogSearchTest` — turn キーの抽出と前後の解決をユニットテストする
      (H2 のインメモリ DB に固定データを入れて検証。外部サービスに触れない)
- [x] 3. `ConversationResource` の `GET /conversations/turn/{logId}` を JSON に変える。
      本文に加えて `sessionId`・`turn`・行の一覧・前後の `logId` とラベルを返す
- [x] 4. `conversations.html` の `showTurn` を書き換える。turn/行のトグル、← / → ボタン、
      左右キーでの移動、端では無効化
- [x] 5. `rm -rf target` してから `mvn install`、実機で検索 → turn 移動 → 行移動を確認する

## Review

### 追加したもの

検索結果を開くと、その行が属する turn の全行が `label` 見出し付きで並ぶ。バーの ← → で会話内の
前後の turn へ移動し、Turn / Row のトグルで行単位に切り替えられる。左右キーでも移動できる。
キー操作はドキュメント全体ではなく開いているリーダー要素に結び付けた。複数の結果を同時に開けること、
検索ボックスへの入力を奪わないことが理由。

`ConversationLogSearch` に `turnView` / `rowView` と `View` / `Row` レコードを追加。
`GET /conversations/turn/{logId}` はプレーンテキストから JSON になり、`mode=turn|row` を取る。
本文だけを返していた `message(long)` は呼ばれなくなったので削除した。

### 実装上の判断

前後の turn は番号の増減ではなく、現在の turn に属する行の `id` の最小・最大を基準に求める。
turn 番号には実データで欠番があり（turn6 の次が turn8）、番号を数えると書かれていない turn を指す。
同じ turn の行が非連続に現れても正しく動く。範囲は必ず同一 `session_id` に閉じる。

turn の行は `label LIKE 'turnN/%'` で取る。末尾のスラッシュがないと `turn1` が `turn10` の行まで拾う。

`id` 順を時系列順として使えるのは、`LogMerger.copyLogs` が 1 会話分の行を連続挿入するため。
マージ済み DB の全行を検査し、`id` 順に対し `timestamp` が逆行する行が 0 件であることを確認した。

### 検証

- ユニットテスト 21 件（既存 9 + 新規 12）、全件緑。全体では 87 件。
- E2E `ConversationTurnNavigationE2E` 36 項目、全件緑。テスト自身が固定データの会話 DB を作り、
  `AI_WORKSPACE_CONVERSATION_LOG_DB_PATH` でポータルをそこへ向ける。turn の欠番、複数行 turn、
  会話の端、別会話への飛び出し、リーダー 2 つの独立、JavaScript エラー 0 件を検査する。
  `AiWorkspaceE2ERunner` に登録済み。
- ヘッドレスブラウザで 12 項目、全件緑（`~/tools/headless-verify/verify_conv_turn_nav.js`）。
  検索 → turn を開く → → で次の turn → ← で戻る → Row 切替 → Turn 復帰 → 右矢印キー、
  複数行 turn の全行にラベルが付くこと、JavaScript エラーが 0 件であること。
- 実データでの API 確認: 22 行の `turn18` の途中の行を開くと全 22 行が並び、← が `turn17`、
  → が `turn19` を指す。同じ行の row モードでは前後が turn 内の隣接行になる。

### E2E で見つかった不具合

矢印をマウスでクリックした後、矢印キーが効かなくなっていた。再描画でクリックしたボタン自体が DOM から
消え、フォーカスが body へ移るため、リーダー要素に結び付けたキーハンドラが以後何も受け取らない。
再描画の最後にリーダーへフォーカスを戻して修正。最初のブラウザ確認が見逃したのは、Playwright が
キー送信の前に対象要素をフォーカスするため、「クリックした直後にキーを押す」という実際の順序を
踏んでいなかったから。`ConversationTurnNavigationE2E` はこの順序をそのまま踏む。

あわせて、行き先が記号だけでツールチップにしか出ていなかったバーを直した。turn モードでは
`← turn1` `turn4 →`、row モードでは行ラベルを出す。会話名とプログラム名は検索結果の見出しに
既にあるのでバーからは削り、`Step by: Turn | Row` と行数を残した。

### バーが押すたびに動く件

Turn / Row を切り替えるとバーの要素が動いていた。原因は 4 つ。
(1) 矢印の文字が turn モードの `turn4` と row モードの `turn1/step2/llm` で長さが違い、flex 行なので
右側が全部ずれる。(2) 現在のモードのボタンを `disabled` にしていたため見た目と反応が変わる。
(3) 選択中を太字にしていたため、そのボタンが太字の分だけ広がって隣を押し出す。
(4) 会話の端で矢印の中身が名前から記号だけに縮む。

バーを 6 つの固定幅セル（戻る矢印・現在位置・進む矢印・行数・`Step by: Turn Row`・キーの案内）の
グリッドにし、はみ出す名前は省略記号で切って全体は `title` に載せた。モードのボタンは両方とも常に
押せる状態・常に太字・固定幅にし、選択は枠と色と背景で示す。端の矢印は `← —` の形で幅を保つ。

E2E は切替の前後で 4 つのボタンの `boundingBox` を実測して比較する。「動かない」を目視ではなく
座標で固定した。この検査が (3) を捕まえた。

### 縦に伸びて画面外へ出る件

chat-ui3 の Sessions と同じ症状。入れ子の `<details>` を開くと文書そのものが伸びるので、turn が
増えても call が増えても本文が長くても同じ 1 本の縦スクロールに積まれ、「いまどの turn のどの call を
見ているか」が上へ流れて画面外へ出る。Conversations も `main` が伸びる作りだったので同じだった。

文書を伸ばすのをやめ、画面をウィンドウの高さに固定して中の領域が独立にスクロールする 3 領域構成に
した。左に検索結果、右上に turn バー、その下に call の一覧、さらに下に選んだ call の全文。
call 一覧は右ペインの高さの 4 割を上限にしてあるので、30 call の turn でも下の本文を押し出さない。

`layout.html` に `body.body-fill` / `main.main-fill` を追加し、Conversations 画面だけこの扱いにする。

あわせて通信量も直した。turn の一覧では各 call の先頭 200 文字だけを `LEFT(message, ?)` で返し、
全文は選んだ 1 件を `mode=row` で取りに行く。実データの 22 call の turn で 8,969 バイト。
以前は 22 件の全文をまとめて返していた。

Turn / Call のモード切替は消えた。turn が容れ物として見えていて call を直接選べる以上、切り替える
対象がない。左右キーと矢印ボタンで turn を移動、上下キーで turn 内の call を移動する。

E2E は 30 call・各 4 kB の turn を用意し、`documentElement.scrollHeight` が `window.innerHeight` を
超えないこと、30 call の最後を選んでも turn 名・次への矢印・選択中の call・検索結果がウィンドウの
矩形の内側に残ることを実測する。

### 「Row」という語

`logs` テーブルの行というデータベース側の語が画面に出ていた。1 行の中身は `REQUEST:/RESPONSE:` か
`TOOL:/INPUT:` のどちらかで、実体は「モデルへの 1 回の呼び出しとその応答」か「ツールへの 1 回の
呼び出しとその入出力」。よって画面では Call と呼ぶ。turn は、人の 1 回の発言に答えるために要した
複数の呼び出しのまとまり。

表示は `Move by: Turn | Call`、件数は `3 calls`。現在位置は call 側で `turn1 · turn1/step1/llm` と
turn 名が二重に出ていたので `turn1 · step1/llm` にした。矢印も call モードでは turn を繰り返さない。

サーバ側の `mode=row` と Java の `rowView` は row のまま。あの層では本当に `logs` の行であり、
画面の語だけを実体に合わせた。

### 途中で見つかった不具合

Qute が `<script>` 内の JavaScript オブジェクトリテラルをテンプレート式として解釈し、画面が 500 に
なっていた。ユニットテストはサーバ側しか見ないので通り、画面を開いて初めて出た。当該スクリプトを
Qute の未解釈ブロック `{| ... |}` で囲んで修正。

### 未対応

- 稼働中の 28000 には反映していない。ビルド済み jar の差し替えと再起動はユーザーの操作を待つ。
