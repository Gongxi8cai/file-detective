import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * file-detective 的最小测试。
 *
 * <p>故意不引入 JUnit：这个项目追求"clone 下来三条命令就能跑"，
 * 多一个依赖就多一道门槛。测试逻辑就是"跑一遍，对一下"。
 *
 * <p>运行（在项目根目录）：
 *
 * <pre>
 *   javac --release 21 -encoding UTF-8 -d out FileDetective.java test/FileDetectiveTest.java
 *   java -cp out FileDetectiveTest
 * </pre>
 */
public class FileDetectiveTest {

    static int ok = 0;
    static int fail = 0;
    static Path samples;

    public static void main(String[] args) throws Exception {
        samples = Path.of(args.length > 0 ? args[0] : "samples");

        System.out.println("样例目录：" + samples.toAbsolutePath());
        System.out.println();

        // 1. 七个样例文件：类型对不对
        erwarteTyp("beispiel.pdf", "pdf");
        erwarteTyp("beispiel.png", "png");
        erwarteTyp("beispiel.jpg", "jpeg");
        erwarteTyp("beispiel.zip", "zip");
        erwarteTyp("beispiel.docx", "docx");
        erwarteTyp("fake.jpg", "png");
        erwarteTyp("beispiel.txt", "text");

        // 2. 扩展名与内容不符时，必须报出来
        FileDetective.Ergebnis fake = FileDetective.untersuche(samples.resolve("fake.jpg"));
        pruefe("fake.jpg 扩展名不符被标记", !fake.endungPasst());
        pruefe("fake.jpg 期望类型是 jpeg", "jpeg".equals(fake.erwartet()));

        // 3. 正常的 .pdf 不应该被误报
        FileDetective.Ergebnis pdf = FileDetective.untersuche(samples.resolve("beispiel.pdf"));
        pruefe("beispiel.pdf 扩展名相符", pdf.endungPasst());
        pruefe("beispiel.pdf 退出码 0", pdf.exitCode() == 0);
        pruefe("beispiel.pdf 头部以 25 50 44 46 开头",
                FileDetective.hex(pdf.kopf()).startsWith("25 50 44 46"));

        // 4. 纯文本要能认出来，并给出编码
        FileDetective.Ergebnis txt = FileDetective.untersuche(samples.resolve("beispiel.txt"));
        pruefe("beispiel.txt 退出码 0", txt.exitCode() == 0);
        pruefe("beispiel.txt 报告 UTF-8", txt.begruendung().contains("UTF-8"));

        // 5. 空文件：不崩，且算"认出来了"
        Path leer = Files.createTempFile("fd-leer-", ".bin");
        try {
            FileDetective.Ergebnis e = FileDetective.untersuche(leer);
            pruefe("空文件识别为 empty", "empty".equals(e.id()));
            pruefe("空文件退出码 0", e.exitCode() == 0);
        } finally {
            Files.deleteIfExists(leer);
        }

        // 6. 二进制垃圾：应该认不出来，退出码 1
        Path binaer = Files.createTempFile("fd-binaer-", ".bin");
        try {
            Files.write(binaer, new byte[] {0, 1, 2, 3, (byte) 0xFF, 0x00});
            FileDetective.Ergebnis e = FileDetective.untersuche(binaer);
            pruefe("二进制垃圾识别为 unknown", "unknown".equals(e.id()));
            pruefe("unknown 退出码 1", e.exitCode() == 1);
        } finally {
            Files.deleteIfExists(binaer);
        }

        // 7. 把 PNG 改名成 .pdf：类型应报 PNG，且提示扩展名不符
        Path getarnt = Files.createTempFile("fd-getarnt-", ".pdf");
        try {
            Files.copy(samples.resolve("beispiel.png"), getarnt, StandardCopyOption.REPLACE_EXISTING);
            FileDetective.Ergebnis e = FileDetective.untersuche(getarnt);
            pruefe("改名成 .pdf 的 PNG 仍报 png", "png".equals(e.id()));
            pruefe("改名后扩展名不符被标记", !e.endungPasst());
        } finally {
            Files.deleteIfExists(getarnt);
        }

        // 8. 不存在的文件：抛 NoSuchFileException，而不是崩成一堆栈
        try {
            FileDetective.untersuche(samples.resolve("gibt-es-nicht.xyz"));
            pruefe("不存在的文件抛异常", false);
        } catch (NoSuchFileException ex) {
            pruefe("不存在的文件抛 NoSuchFileException", true);
        }

        // 9. 全项目第一课：byte 有符号，& 0xFF 才变成 0..255
        pruefe("(byte)0x89 & 0xFF == 137", FileDetective.unsigned((byte) 0x89) == 137);
        pruefe("(byte)0xFF & 0xFF == 255", FileDetective.unsigned((byte) 0xFF) == 255);

        // 10. 扩展名解析
        pruefe("扩展名 .PDF 统一成小写", "pdf".equals(FileDetective.endungVon(Path.of("A.PDF"))));
        pruefe("没有扩展名返回 null", FileDetective.endungVon(Path.of("README")) == null);

        System.out.println();
        System.out.println(ok + " 通过, " + fail + " 失败");
        if (fail > 0) {
            System.exit(1);
        }
    }

    static void erwarteTyp(String datei, String typ) throws IOException {
        FileDetective.Ergebnis e = FileDetective.untersuche(samples.resolve(datei));
        gleich(datei + " 类型", typ, e.id());
    }

    static void pruefe(String name, boolean bedingung) {
        if (bedingung) {
            ok++;
            System.out.println("[OK] " + name);
        } else {
            fail++;
            System.out.println("[X]  " + name);
        }
    }

    static void gleich(String name, Object erwartet, Object ist) {
        boolean bedingung = erwartet == null ? ist == null : erwartet.equals(ist);
        if (!bedingung) {
            System.out.println("      期望 " + erwartet + "，实际 " + ist);
        }
        pruefe(name, bedingung);
    }
}
