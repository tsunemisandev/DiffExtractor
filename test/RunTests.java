import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * ExtractDiffs の動作テスト
 *
 * 使い方:
 *   cd test
 *   javac -cp .. RunTests.java
 *   java -cp .;.. RunTests
 */
public class RunTests {

    record TestCase(String name, String patchFile, String description, String gitRepoDir) {
        TestCase(String name, String patchFile, String description) {
            this(name, patchFile, description, null);
        }
    }

    static int passed = 0;
    static int failed = 0;

    // case09 用: セットアップ時に保存した元のバイナリ内容
    static byte[] case09OriginalBytes = null;

    public static void main(String[] args) throws Exception {
        Path testDir  = Path.of(".").toAbsolutePath().normalize();
        Path patchDir = testDir.resolve("patches");
        Path outBase  = testDir.resolve("output");

        // 前回の出力を削除
        deleteDir(outBase);
        Files.createDirectories(outBase);

        // case09: 実際の git リポジトリをセットアップしてパッチを生成
        String case09RepoDir = setupCase09(testDir, patchDir);

        List<TestCase> cases = List.of(
            new TestCase("case01", "case01_simple_edit.patch",    "単純な行編集"),
            new TestCase("case02", "case02_new_file.patch",       "新規ファイル追加"),
            new TestCase("case03", "case03_delete_file.patch",    "ファイル削除"),
            new TestCase("case04", "case04_multi_file.patch",     "複数ファイル変更"),
            new TestCase("case05", "case05_multi_hunk.patch",     "複数ハンク（同一ファイル）"),
            new TestCase("case06", "case06_japanese_folder.patch","フォルダパスが日本語"),
            new TestCase("case07", "case07_japanese_filename.patch","ファイル名が日本語"),
            new TestCase("case08", "case08_binary_file.patch",    "バイナリファイル混在（スキップ確認）"),
            new TestCase("case09", "case09_binary_blob.patch",    "バイナリを git blob から取得", case09RepoDir)
        );

        System.out.println("========================================");
        System.out.println(" ExtractDiffs テスト実行");
        System.out.println("========================================\n");

        for (TestCase tc : cases) {
            runCase(tc, patchDir, outBase);
        }

        System.out.println("\n========================================");
        System.out.printf(" 結果: %d 件成功 / %d 件失敗%n", passed, failed);
        System.out.println("========================================");
    }

    // ----------------------------------------------------------------
    // case09 セットアップ: 実際の git リポジトリを作成してパッチを生成
    // ----------------------------------------------------------------
    static String setupCase09(Path testDir, Path patchDir) {
        Path repoDir = testDir.resolve("output/case09_repo");
        try {
            Files.createDirectories(repoDir.resolve("data"));

            runGit(repoDir, "init");
            runGit(repoDir, "config", "user.email", "test@test.com");
            runGit(repoDir, "config", "user.name", "Test");

            // 元のバイナリファイル (null バイトを含む明確なバイナリデータ)
            case09OriginalBytes = new byte[]{
                0x00, 0x01, 0x02, 0x03,
                (byte) 0xFF, (byte) 0xFE, (byte) 0xFD, (byte) 0xFC,
                0x41, 0x42, 0x43, 0x44  // ABCD
            };
            Files.write(repoDir.resolve("data/sample.bin"), case09OriginalBytes);

            runGit(repoDir, "add", ".");
            runGit(repoDir, "commit", "-m", "initial commit");

            // バイナリファイルを変更して 2 つ目のコミット
            byte[] modified = new byte[]{
                0x00, 0x01, 0x02, 0x03,
                (byte) 0xFF, (byte) 0xFE, (byte) 0xFD, (byte) 0xFC,
                0x45, 0x46, 0x47, 0x48  // EFGH
            };
            Files.write(repoDir.resolve("data/sample.bin"), modified);
            runGit(repoDir, "add", ".");
            runGit(repoDir, "commit", "-m", "update binary");

            // git diff HEAD~1 HEAD でコミット間の差分を取得
            // → 両ハッシュが実際の blob オブジェクトとして存在する
            Process p = new ProcessBuilder("git", "diff", "HEAD~1", "HEAD")
                    .directory(repoDir.toFile())
                    .start();
            byte[] diffBytes = p.getInputStream().readAllBytes();
            p.waitFor();

            Path patchFile = patchDir.resolve("case09_binary_blob.patch");
            Files.write(patchFile, diffBytes);

            System.out.println("[setup] case09 git リポジトリ作成完了: " + repoDir);
            System.out.println("[setup] パッチ保存: " + patchFile.getFileName());
            System.out.println();
            return repoDir.toString();

        } catch (Exception e) {
            System.out.println("[setup] case09 セットアップ失敗: " + e.getMessage());
            return repoDir.toString();
        }
    }

    static void runGit(Path dir, String... gitArgs) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(gitArgs));
        Process p = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out  = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("git " + String.join(" ", gitArgs)
                    + " 失敗 (コード " + exitCode + ")\n" + out);
        }
    }

    // ----------------------------------------------------------------
    // テスト実行
    // ----------------------------------------------------------------
    static void runCase(TestCase tc, Path patchDir, Path outBase) throws Exception {
        Path patch  = patchDir.resolve(tc.patchFile());
        Path outDir = outBase.resolve(tc.name());

        System.out.printf("[%s] %s%n", tc.name(), tc.description());
        System.out.println("  patch: " + patch.getFileName());

        try {
            String[] extractArgs = (tc.gitRepoDir() != null)
                    ? new String[]{ patch.toString(), outDir.toString(), tc.gitRepoDir() }
                    : new String[]{ patch.toString(), outDir.toString() };

            ExtractDiffs.main(extractArgs);
            checkResult(tc, outDir);

        } catch (Exception e) {
            System.out.println("  [FAIL] 例外が発生: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
        System.out.println();
    }

    // ----------------------------------------------------------------
    // 結果チェック
    // ----------------------------------------------------------------
    static void checkResult(TestCase tc, Path outDir) throws IOException {
        Path beforeRoot = outDir.resolve("修正前");
        Path afterRoot  = outDir.resolve("修正後");

        switch (tc.name()) {
            case "case01" -> {
                assertFileContains(beforeRoot.resolve("src/Hello.java"), "Hello World",  tc.name());
                assertFileContains(afterRoot .resolve("src/Hello.java"), "Hello, Java!", tc.name());
            }
            case "case02" -> {
                assertExists   (afterRoot .resolve("src/NewClass.java"), tc.name());
                assertNotExists(beforeRoot.resolve("src/NewClass.java"), tc.name());
            }
            case "case03" -> {
                assertExists   (beforeRoot.resolve("src/OldClass.java"), tc.name());
                assertNotExists(afterRoot .resolve("src/OldClass.java"), tc.name());
            }
            case "case04" -> {
                for (String f : new String[]{
                        "src/main/App.java", "config/app.properties", "README.md"}) {
                    assertFileContains(beforeRoot.resolve(f), "", tc.name());
                    assertFileContains(afterRoot .resolve(f), "", tc.name());
                }
                assertFileContains(afterRoot.resolve("src/main/App.java"),       "2.0.0",       tc.name());
                assertFileContains(afterRoot.resolve("config/app.properties"),   "debug=false", tc.name());
            }
            case "case05" -> {
                assertFileContains(afterRoot.resolve("src/Calculator.java"), "足し算",       tc.name());
                assertFileContains(afterRoot.resolve("src/Calculator.java"), "掛け算",       tc.name());
                assertFileContains(afterRoot.resolve("src/Calculator.java"), "multiplyExact", tc.name());
            }
            case "case06" -> {
                assertFileContains(beforeRoot.resolve("src/ソース/メイン処理/App.java"), "旧バージョン", tc.name());
                assertFileContains(afterRoot .resolve("src/ソース/メイン処理/App.java"), "新バージョン", tc.name());
                assertFileContains(afterRoot .resolve("src/ソース/メイン処理/App.java"), "アプリ起動",   tc.name());
            }
            case "case07" -> {
                assertFileContains(beforeRoot.resolve("config/設定ファイル.properties"), "バージョン=1.0", tc.name());
                assertFileContains(afterRoot .resolve("config/設定ファイル.properties"), "バージョン=2.0", tc.name());
                assertFileContains(afterRoot .resolve("config/設定ファイル.properties"), "デバッグ=無効",  tc.name());
                assertExists   (afterRoot .resolve("docs/仕様書.md"), tc.name());
                assertNotExists(beforeRoot.resolve("docs/仕様書.md"), tc.name());
                assertFileContains(afterRoot.resolve("docs/仕様書.md"), "概要", tc.name());
            }
            case "case08" -> {
                // バイナリはスキップされること
                assertNotExists(beforeRoot.resolve("images/logo.png"),       tc.name());
                assertNotExists(afterRoot .resolve("images/logo.png"),       tc.name());
                assertNotExists(beforeRoot.resolve("assets/background.jpg"), tc.name());
                assertNotExists(afterRoot .resolve("assets/background.jpg"), tc.name());
                // 同じパッチ内のテキストファイルは正常に出力されること
                assertFileContains(beforeRoot.resolve("src/Main.java"), "VERSION = \"1.0\"", tc.name());
                assertFileContains(afterRoot .resolve("src/Main.java"), "VERSION = \"2.0\"", tc.name());
            }
            case "case09" -> {
                // 修正前: git blob から取得した元のバイナリ内容と一致すること
                assertBinaryContent(beforeRoot.resolve("data/sample.bin"),
                                    case09OriginalBytes, tc.name());
                // 修正後: ファイルが存在すること (内容は変更後)
                assertExists(afterRoot.resolve("data/sample.bin"), tc.name());
                // 修正後の末尾 4 バイトは 0x45,0x46,0x47,0x48 (EFGH)
                assertBinaryEndsWith(afterRoot.resolve("data/sample.bin"),
                                     new byte[]{0x45, 0x46, 0x47, 0x48}, tc.name());
            }
        }
    }

    // ---- アサーション ------------------------------------------------

    static void assertExists(Path p, String caseName) {
        if (Files.exists(p)) {
            System.out.println("  [PASS] 存在確認: " + relativize(p));
            passed++;
        } else {
            System.out.println("  [FAIL] ファイルが存在しない: " + relativize(p));
            failed++;
        }
    }

    static void assertNotExists(Path p, String caseName) {
        if (!Files.exists(p)) {
            System.out.println("  [PASS] 不在確認: " + relativize(p));
            passed++;
        } else {
            System.out.println("  [FAIL] 存在すべきでないファイルが存在する: " + relativize(p));
            failed++;
        }
    }

    static void assertFileContains(Path p, String keyword, String caseName) throws IOException {
        if (!Files.exists(p)) {
            System.out.println("  [FAIL] ファイルが存在しない: " + relativize(p));
            failed++;
            return;
        }
        String content = Files.readString(p);
        if (keyword.isEmpty() || content.contains(keyword)) {
            System.out.println("  [PASS] 内容確認: " + relativize(p)
                    + (keyword.isEmpty() ? "" : " (\"" + keyword + "\")"));
            passed++;
        } else {
            System.out.println("  [FAIL] キーワードが見つからない: \"" + keyword
                    + "\" in " + relativize(p));
            failed++;
        }
    }

    static void assertBinaryContent(Path p, byte[] expected, String caseName) throws IOException {
        if (!Files.exists(p)) {
            System.out.println("  [FAIL] バイナリファイルが存在しない: " + relativize(p));
            failed++;
            return;
        }
        byte[] actual = Files.readAllBytes(p);
        if (Arrays.equals(actual, expected)) {
            System.out.println("  [PASS] バイナリ内容一致: " + relativize(p)
                    + " (" + actual.length + " bytes)");
            passed++;
        } else {
            System.out.println("  [FAIL] バイナリ内容不一致: " + relativize(p)
                    + " 期待=" + toHex(expected) + " 実際=" + toHex(actual));
            failed++;
        }
    }

    static void assertBinaryEndsWith(Path p, byte[] suffix, String caseName) throws IOException {
        if (!Files.exists(p)) {
            System.out.println("  [FAIL] バイナリファイルが存在しない: " + relativize(p));
            failed++;
            return;
        }
        byte[] actual = Files.readAllBytes(p);
        boolean matches = actual.length >= suffix.length;
        if (matches) {
            int offset = actual.length - suffix.length;
            for (int i = 0; i < suffix.length; i++) {
                if (actual[offset + i] != suffix[i]) { matches = false; break; }
            }
        }
        if (matches) {
            System.out.println("  [PASS] バイナリ末尾確認: " + relativize(p)
                    + " 末尾=" + toHex(suffix));
            passed++;
        } else {
            System.out.println("  [FAIL] バイナリ末尾不一致: " + relativize(p)
                    + " 期待末尾=" + toHex(suffix));
            failed++;
        }
    }

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder("[");
        for (byte v : b) sb.append(String.format("%02X ", v));
        return sb.toString().trim() + "]";
    }

    static String relativize(Path p) {
        try {
            return Path.of(".").toAbsolutePath().normalize().relativize(p).toString();
        } catch (Exception e) {
            return p.toString();
        }
    }

    // ---- ユーティリティ ----------------------------------------------

    static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }
}
