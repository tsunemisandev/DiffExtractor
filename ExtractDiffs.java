import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * 使い方:
 *   git diff > changes.patch
 *   java ExtractDiffs changes.patch C:\MyFolderPath [git_repo_dir]
 *
 *   または標準入力から:
 *   git diff | java ExtractDiffs - C:\MyFolderPath
 *
 * 出力:
 *   C:\MyFolderPath\
 *     修正前\  ... 変更前のファイル
 *     修正後\  ... 変更後のファイル
 *
 * バイナリファイルは diff の index 行に記録された blob ハッシュを使い
 * git cat-file blob <hash> で元のファイルを取得して格納します。
 * git_repo_dir を省略した場合はカレントディレクトリを git リポジトリとして使用します。
 */
public class ExtractDiffs {

    static final Pattern DIFF_GIT    = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");
    static final Pattern INDEX_LINE  = Pattern.compile("^index ([0-9a-f]+)\\.\\. ?([0-9a-f]+)");

    record Hunk(int oldStart, int newStart, List<String> lines) {}

    record FileInfo(String path, List<Hunk> hunks,
                    String oldHash, String newHash, boolean isBinary) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            System.out.println("使い方: java ExtractDiffs <patch_file|-> <output_dir> [git_repo_dir]");
            System.out.println("例:     java ExtractDiffs changes.patch C:\\MyFolderPath");
            System.out.println("例:     java ExtractDiffs changes.patch C:\\MyFolderPath C:\\MyRepo");
            System.out.println("例:     git diff | java ExtractDiffs - C:\\MyFolderPath");
            System.exit(1);
        }

        String patchArg  = args[0];
        String outputDir = args[1];
        Path   repoDir   = Path.of(args.length == 3 ? args[2] : ".").toAbsolutePath().normalize();

        // diff テキスト読み込み
        List<String> lines;
        if ("-".equals(patchArg)) {
            lines = new BufferedReader(new InputStreamReader(System.in, "UTF-8"))
                    .lines().toList();
        } else {
            lines = Files.readAllLines(Path.of(patchArg));
        }

        List<FileInfo> files = parseDiff(lines);

        if (files.isEmpty()) {
            System.out.println("変更されたファイルが見つかりませんでした。");
            return;
        }

        Path beforeRoot = Path.of(outputDir, "修正前");
        Path afterRoot  = Path.of(outputDir, "修正後");

        for (FileInfo fi : files) {
            if (fi.isBinary()) {
                handleBinaryFile(fi, beforeRoot, afterRoot, repoDir);
            } else {
                List<String> beforeLines = new ArrayList<>();
                List<String> afterLines  = new ArrayList<>();
                reconstructVersions(fi, beforeLines, afterLines);

                if (!beforeLines.isEmpty()) {
                    writeTextFile(beforeRoot.resolve(fi.path()), beforeLines);
                    System.out.println("  修正前: " + fi.path());
                }
                if (!afterLines.isEmpty()) {
                    writeTextFile(afterRoot.resolve(fi.path()), afterLines);
                    System.out.println("  修正後: " + fi.path());
                }
            }
        }

        System.out.println("\n完了: " + outputDir);
        System.out.println("  修正前 -> " + beforeRoot);
        System.out.println("  修正後 -> " + afterRoot);
    }

    // ----------------------------------------------------------------
    // unified diff の解析
    // ----------------------------------------------------------------
    static List<FileInfo> parseDiff(List<String> lines) {
        List<FileInfo> files = new ArrayList<>();
        String       currentPath     = null;
        List<Hunk>   currentHunks    = null;
        String       currentOldHash  = null;
        String       currentNewHash  = null;
        boolean      currentIsBinary = false;

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);

            // diff --git a/... b/...
            Matcher dm = DIFF_GIT.matcher(line);
            if (dm.matches()) {
                if (currentPath != null) {
                    files.add(new FileInfo(currentPath, currentHunks,
                                           currentOldHash, currentNewHash, currentIsBinary));
                }
                currentPath     = dm.group(2);
                currentHunks    = new ArrayList<>();
                currentOldHash  = null;
                currentNewHash  = null;
                currentIsBinary = false;
                i++;
                continue;
            }

            // index <old>..<new> [mode]  ← blob ハッシュを取得
            Matcher im = INDEX_LINE.matcher(line);
            if (im.find()) {
                currentOldHash = im.group(1);
                currentNewHash = im.group(2);
                i++;
                continue;
            }

            // Binary files ... differ
            if (line.startsWith("Binary files")) {
                currentIsBinary = true;
                i++;
                continue;
            }

            // ハンク開始 @@
            Matcher hm = HUNK_HEADER.matcher(line);
            if (hm.find()) {
                int oldStart = Integer.parseInt(hm.group(1));
                int newStart = Integer.parseInt(hm.group(3));
                List<String> hunkLines = new ArrayList<>();
                i++;
                while (i < lines.size()
                        && !lines.get(i).startsWith("@@")
                        && !lines.get(i).startsWith("diff ")) {
                    hunkLines.add(lines.get(i));
                    i++;
                }
                if (currentHunks != null) {
                    currentHunks.add(new Hunk(oldStart, newStart, hunkLines));
                }
                continue;
            }

            i++;
        }

        if (currentPath != null) {
            files.add(new FileInfo(currentPath, currentHunks,
                                   currentOldHash, currentNewHash, currentIsBinary));
        }

        return files;
    }

    // ----------------------------------------------------------------
    // バイナリファイル: git cat-file blob <hash> で取得して保存
    // ----------------------------------------------------------------
    static void handleBinaryFile(FileInfo fi, Path beforeRoot, Path afterRoot, Path repoDir) {
        if (!isZeroHash(fi.oldHash())) {
            boolean ok = extractBinaryViaGit(fi.oldHash(), beforeRoot.resolve(fi.path()), repoDir);
            System.out.println("  修正前(binary): " + fi.path() + (ok ? "" : " [取得失敗: git cat-file が使えません]"));
        }
        if (!isZeroHash(fi.newHash())) {
            boolean ok = extractBinaryViaGit(fi.newHash(), afterRoot.resolve(fi.path()), repoDir);
            System.out.println("  修正後(binary): " + fi.path() + (ok ? "" : " [取得失敗: git cat-file が使えません]"));
        }
    }

    static boolean extractBinaryViaGit(String hash, Path dest, Path repoDir) {
        try {
            Process p = new ProcessBuilder("git", "cat-file", "blob", hash)
                    .directory(repoDir.toFile())
                    .redirectErrorStream(false)
                    .start();
            byte[] data     = p.getInputStream().readAllBytes();
            int    exitCode = p.waitFor();
            if (exitCode != 0) {
                System.err.println("  [警告] git cat-file blob " + hash
                        + " 失敗 (終了コード " + exitCode + ")");
                return false;
            }
            Files.createDirectories(dest.getParent());
            Files.write(dest, data);
            return true;
        } catch (Exception e) {
            System.err.println("  [警告] バイナリ取得エラー: " + e.getMessage());
            return false;
        }
    }

    static boolean isZeroHash(String hash) {
        return hash == null || hash.matches("0+");
    }

    // ----------------------------------------------------------------
    // 修正前 / 修正後 のテキスト行リストを再構築
    // ----------------------------------------------------------------
    static void reconstructVersions(FileInfo fi,
                                    List<String> before,
                                    List<String> after) {
        for (Hunk hunk : fi.hunks()) {
            for (String raw : hunk.lines()) {
                if (raw.startsWith("\\ ")) continue; // "\ No newline at end of file"

                if (raw.startsWith("-")) {
                    before.add(raw.substring(1));
                } else if (raw.startsWith("+")) {
                    after.add(raw.substring(1));
                } else {
                    String content = raw.startsWith(" ") ? raw.substring(1) : raw;
                    before.add(content);
                    after.add(content);
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // テキストファイル書き出し
    // ----------------------------------------------------------------
    static void writeTextFile(Path dest, List<String> lines) throws IOException {
        Files.createDirectories(dest.getParent());
        Files.writeString(dest, String.join("\n", lines));
    }
}
