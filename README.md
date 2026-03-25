# ExtractDiffs

`git diff` の出力を解析し、変更前・変更後のファイルを指定フォルダに展開するコマンドラインツールです。

## 必要環境

- Java 16 以上（`record` 構文を使用）※ Java 17 LTS 推奨
- Git（バイナリファイルの取得に使用）

## ビルド

```bash
javac ExtractDiffs.java
```

## 使い方

### パッチファイルから実行

```bash
git diff > changes.patch
java ExtractDiffs changes.patch C:\MyFolderPath
```

### 標準入力から直接実行

```bash
git diff | java ExtractDiffs - C:\MyFolderPath
```

### コミット間の差分を指定

```bash
git diff HEAD~1 HEAD > changes.patch
java ExtractDiffs changes.patch C:\MyFolderPath C:\MyRepo
```

> `git_repo_dir` を省略した場合はカレントディレクトリを git リポジトリとして使用します。

## 出力構造

```
C:\MyFolderPath\
  修正前\          ← 変更前のファイル
    src\
      Main.java
  修正後\          ← 変更後のファイル
    src\
      Main.java
```

サブフォルダの階層はリポジトリ内のパスがそのまま維持されます。

## 対応ケース

| ケース | 動作 |
|--------|------|
| テキストファイルの編集 | ハンクから修正前・修正後を再構築して格納 |
| 新規ファイル追加 | 修正後のみ格納 |
| ファイル削除 | 修正前のみ格納 |
| 複数ファイルの変更 | ファイルごとに分離して格納 |
| 同一ファイル内の複数ハンク | 全ハンクをまとめて再構築 |
| 日本語フォルダ名・ファイル名 | そのままのパスで格納 |
| バイナリファイル | `git cat-file blob <hash>` で元データを取得して格納 |

### バイナリファイルについて

`git diff` の index 行に記録された blob ハッシュを使って git オブジェクトDBからバイナリを取得します。

```
index abc1234..def5678 100644
      ↑ 修正前blob   ↑ 修正後blob
```

**注意:** `git diff`（未コミットの作業ツリー差分）の場合、修正後の blob はまだ git オブジェクトとして登録されていないため取得できません。コミット間の差分（`git diff HEAD~1 HEAD` など）を使うと修正前・修正後の両方が取得できます。

| コマンド | 修正前 | 修正後 |
|---------|--------|--------|
| `git diff`（未コミット） | 取得可 | 取得不可 |
| `git diff HEAD~1 HEAD` | 取得可 | 取得可 |

## テスト

```bash
cd test
javac -cp .. RunTests.java
java -cp ".;.." RunTests
```

### テストシナリオ一覧

| ケース | 内容 |
|--------|------|
| case01 | 単純な行編集 |
| case02 | 新規ファイル追加 |
| case03 | ファイル削除 |
| case04 | 複数ファイル同時変更 |
| case05 | 同一ファイル内の複数ハンク |
| case06 | フォルダパスが日本語 |
| case07 | ファイル名が日本語 |
| case08 | バイナリファイル混在（git リポジトリなし → スキップ確認） |
| case09 | バイナリファイルを git blob から取得（実際の git リポジトリを使用） |

## ファイル構成

```
ExtractDiffs.java        ← メインツール
README.md
.gitignore
test/
  RunTests.java          ← テストランナー
  patches/               ← テスト用サンプルパッチ
    case01_simple_edit.patch
    case02_new_file.patch
    case03_delete_file.patch
    case04_multi_file.patch
    case05_multi_hunk.patch
    case06_japanese_folder.patch
    case07_japanese_filename.patch
    case08_binary_file.patch
  output/                ← テスト実行結果（自動生成・.gitignore 対象）
```
