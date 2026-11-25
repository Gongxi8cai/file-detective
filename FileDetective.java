import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * file-detective · 不看扩展名，只看文件开头，判断它到底是什么。
 *
 * <p>这个程序只做三件事，但每一件都值得单独讲：
 *
 * <ol>
 *   <li><b>只读开头 16 个字节。</b> 判断类型不需要读完整个文件——一个 4 GB 的视频
 *       也只读 16 字节。这叫"按需读取"，是性能设计，不是偷懒。</li>
 *   <li><b>用一张表做判定。</b> 每支持一种新格式，只需要在 {@link #TABELLE} 里加一行。
 *       这叫"表驱动"，比写一长串 if-else 更好扩展。</li>
 *   <li><b>把 byte 变成 0..255 的 int。</b> Java 的 byte 是有符号的：0x89 存进去是
 *       -119。直接和 0x89 比较永远不相等，必须先 {@code b & 0xFF}。这是本项目第一课，
 *       也是后面所有字节操作的地基。</li>
 * </ol>
 *
 * <p>用法：
 *
 * <pre>
 *   java FileDetective 文件路径
 *   java FileDetective --json 文件路径 [更多文件...]
 * </pre>
 *
 * <p>退出码：0 = 认出来了（含纯文本），1 = 认不出来，2 = 参数或读取错误。
 */
public class FileDetective {

    /** 判定类型只需要文件开头的这么多字节。 */
    static final int KOPF_BYTES = 16;

    /** 判断"像不像文本"时最多看这么多字节。 */
    static final int TEXT_PROBE = 4096;

    /** 表里的一行：从 offset 开始的这些字节必须一模一样。 */
    record Magie(int offset, byte[] bytes) {}

    /** 一种文件格式：id + 人话名字 + 怎么打开 + 一组可选的魔数。 */
    record Format(String id, String name, String openWith, List<Magie> magics) {}

    /** ZIP 家族细分后的结果。 */
    record ZipArt(String id, String name, String openWith, String hinweis) {}

    /** 一次检测的完整结论。用 record 装起来，避免五个参数到处传。 */
    record Ergebnis(
            Path pfad,
            long groesse,
            byte[] kopf,
            String id,
            String typName,
            String begruendung,
            String oeffnen,
            String zipArt,
            String endung,
            boolean endungBekannt,
            boolean endungPasst,
            String erwartet,
            int exitCode) {}

    /** 文本探测的结果。 */
    record TextBefund(boolean istText, String encoding, String hinweis) {}

    // ------------------------------------------------------------------
    // 魔数表：加一种格式 = 加一行。这就是"可扩展"的具体含义。
    // ------------------------------------------------------------------

    static final List<Format> TABELLE = List.of(
            f("pdf", "PDF 文档", "浏览器 / Adobe Acrobat / Edge",
                    m(0, 0x25, 0x50, 0x44, 0x46)),
            f("png", "PNG 图片", "照片查看器 / 浏览器",
                    m(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
            f("jpeg", "JPEG 图片", "照片查看器 / 浏览器",
                    m(0, 0xFF, 0xD8, 0xFF)),
            f("gif", "GIF 图片", "浏览器 / 照片查看器",
                    m(0, 0x47, 0x49, 0x46, 0x38)),
            f("bmp", "BMP 图片", "照片查看器",
                    m(0, 0x42, 0x4D)),
            f("tiff", "TIFF 图片", "照片查看器",
                    m(0, 0x49, 0x49, 0x2A, 0x00), m(0, 0x4D, 0x4D, 0x00, 0x2A)),
            f("webp", "WebP 图片", "照片查看器 / 浏览器",
                    m(0, 0x52, 0x49, 0x46, 0x46), m(8, 0x57, 0x45, 0x42, 0x50)),
            f("wav", "WAV 音频", "媒体播放器",
                    m(0, 0x52, 0x49, 0x46, 0x46), m(8, 0x57, 0x41, 0x56, 0x45)),
            f("avi", "AVI 视频", "媒体播放器",
                    m(0, 0x52, 0x49, 0x46, 0x46), m(8, 0x41, 0x56, 0x49, 0x20)),
            f("zip", "ZIP 压缩包", "解压软件 / 资源管理器",
                    m(0, 0x50, 0x4B, 0x03, 0x04), m(0, 0x50, 0x4B, 0x05, 0x06),
                    m(0, 0x50, 0x4B, 0x07, 0x08)),
            f("gzip", "GZIP 压缩包", "解压软件",
                    m(0, 0x1F, 0x8B)),
            f("7z", "7-Zip 压缩包", "7-Zip / 解压软件",
                    m(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)),
            f("rar", "RAR 压缩包", "WinRAR / 7-Zip",
                    m(0, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)),
            f("elf", "ELF 可执行文件", "Linux 系统（Windows 不能直接运行）",
                    m(0, 0x7F, 0x45, 0x4C, 0x46)),
            f("pe", "Windows 可执行文件 (EXE/DLL)", "双击运行（来源不明时不要运行）",
                    m(0, 0x4D, 0x5A)),
            f("sqlite", "SQLite 数据库", "DB Browser for SQLite",
                    m(0, 0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66, 0x6F, 0x72,
                            0x6D, 0x61, 0x74, 0x20, 0x33, 0x00)),
            f("mp3", "MP3 音频", "媒体播放器",
                    m(0, 0x49, 0x44, 0x33), m(0, 0xFF, 0xFB), m(0, 0xFF, 0xF3),
                    m(0, 0xFF, 0xF2)),
            f("ogg", "OGG 音频", "媒体播放器",
                    m(0, 0x4F, 0x67, 0x67, 0x53)),
            f("flac", "FLAC 无损音频", "媒体播放器",
                    m(0, 0x66, 0x4C, 0x61, 0x43)),
            f("mp4", "MP4 视频", "媒体播放器 / 浏览器",
                    m(4, 0x66, 0x74, 0x79, 0x70)),
            f("mkv", "Matroska 视频 (MKV/WebM)", "VLC / 媒体播放器",
                    m(0, 0x1A, 0x45, 0xDF, 0xA3)),
            f("doc", "旧版 Office 文档 (OLE)", "Word / Excel / WPS",
                    m(0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)),
            f("psd", "Photoshop 文档", "Photoshop / GIMP",
                    m(0, 0x38, 0x42, 0x50, 0x53)),
            f("class", "Java 字节码", "用 java 命令运行",
                    m(0, 0xCA, 0xFE, 0xBA, 0xBE)),
            f("wasm", "WebAssembly 模块", "浏览器 / wasmtime",
                    m(0, 0x00, 0x61, 0x73, 0x6D)),
            f("ttf", "TrueType 字体", "双击预览 / 安装",
                    m(0, 0x00, 0x01, 0x00, 0x00, 0x00)),
            f("woff2", "WOFF2 字体", "浏览器",
                    m(0, 0x77, 0x4F, 0x46, 0x32)),
            f("ico", "ICO 图标", "照片查看器",
                    m(0, 0x00, 0x00, 0x01, 0x00)));

    /** 扩展名 → 期望的类型 id。用来抓"披着羊皮的狼"。 */
    static final Map<String, String> ENDUNGEN = Map.ofEntries(
            Map.entry("pdf", "pdf"),
            Map.entry("png", "png"),
            Map.entry("jpg", "jpeg"),
            Map.entry("jpeg", "jpeg"),
            Map.entry("gif", "gif"),
            Map.entry("bmp", "bmp"),
            Map.entry("tif", "tiff"),
            Map.entry("tiff", "tiff"),
            Map.entry("webp", "webp"),
            Map.entry("zip", "zip"),
            Map.entry("docx", "docx"),
            Map.entry("xlsx", "xlsx"),
            Map.entry("pptx", "pptx"),
            Map.entry("jar", "jar"),
            Map.entry("apk", "apk"),
            Map.entry("gz", "gzip"),
            Map.entry("7z", "7z"),
            Map.entry("rar", "rar"),
            Map.entry("exe", "pe"),
            Map.entry("dll", "pe"),
            Map.entry("mp3", "mp3"),
            Map.entry("mp4", "mp4"),
            Map.entry("mov", "mp4"),
            Map.entry("mkv", "mkv"),
            Map.entry("webm", "mkv"),
            Map.entry("wav", "wav"),
            Map.entry("avi", "avi"),
            Map.entry("ogg", "ogg"),
            Map.entry("flac", "flac"),
            Map.entry("doc", "doc"),
            Map.entry("psd", "psd"),
            Map.entry("class", "class"),
            Map.entry("wasm", "wasm"),
            Map.entry("ttf", "ttf"),
            Map.entry("woff2", "woff2"),
            Map.entry("ico", "ico"),
            Map.entry("txt", "text"),
            Map.entry("md", "text"),
            Map.entry("csv", "text"),
            Map.entry("json", "text"),
            Map.entry("xml", "text"),
            Map.entry("html", "text"),
            Map.entry("htm", "text"),
            Map.entry("java", "text"),
            Map.entry("py", "text"),
            Map.entry("js", "text"),
            Map.entry("css", "text"),
            Map.entry("yml", "text"),
            Map.entry("yaml", "text"),
            Map.entry("toml", "text"),
            Map.entry("log", "text"),
            Map.entry("ini", "text"),
            Map.entry("sh", "text"),
            Map.entry("bat", "text"),
            Map.entry("ps1", "text"));

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        boolean json = false;
        List<String> dateien = new ArrayList<>();

        for (String arg : args) {
            switch (arg) {
                case "--json" -> json = true;
                case "-h", "--help" -> {
                    hilfe();
                    return;
                }
                default -> {
                    if (arg.startsWith("--")) {
                        System.err.println("未知参数：" + arg);
                        hilfe();
                        System.exit(2);
                    }
                    dateien.add(arg);
                }
            }
        }

        if (dateien.isEmpty()) {
            hilfe();
            System.exit(2);
        }

        List<Ergebnis> ergebnisse = new ArrayList<>();
        int exitCode = 0;

        for (String datei : dateien) {
            try {
                Ergebnis e = untersuche(Path.of(datei));
                ergebnisse.add(e);
                exitCode = Math.max(exitCode, e.exitCode());
            } catch (NoSuchFileException ex) {
                System.err.println("[X] 文件不存在：" + datei);
                exitCode = 2;
            } catch (Exception ex) {
                System.err.println("[X] 读不了 " + datei + "：" + ex.getMessage());
                exitCode = 2;
            }
        }

        if (json) {
            druckeJson(ergebnisse);
        } else {
            for (Ergebnis e : ergebnisse) {
                drucke(e);
            }
        }

        System.exit(exitCode);
    }

    static void hilfe() {
        System.out.println("""
                file-detective · 只看文件头，判断它到底是什么

                用法：
                  java FileDetective <文件> [更多文件...]
                  java FileDetective --json <文件> [更多文件...]

                例：
                  java FileDetective samples/beispiel.pdf
                  java FileDetective --json samples/fake.jpg

                退出码：
                  0  认出来了（含纯文本）
                  1  认不出来
                  2  参数错误 / 文件读不了

                判定只读文件开头 16 个字节，不会把整个大文件读进内存。
                """);
    }

    // ------------------------------------------------------------------
    // 核心：检测
    // ------------------------------------------------------------------

    static Ergebnis untersuche(Path pfad) throws IOException {
        if (!Files.exists(pfad)) {
            throw new NoSuchFileException(pfad.toString());
        }
        if (!Files.isRegularFile(pfad)) {
            throw new IOException("这不是普通文件（可能是目录）：" + pfad);
        }

        long groesse = Files.size(pfad);
        byte[] kopf = liesKopf(pfad);
        String endung = endungVon(pfad);
        String erwartet = endung == null ? null : ENDUNGEN.get(endung);
        boolean endungBekannt = erwartet != null;

        // 空文件：没有字节可看，但它确实是一种"可识别"的状态。
        if (groesse == 0) {
            return new Ergebnis(pfad, 0, kopf, "empty", "空文件",
                    "文件大小是 0 字节", "—", null, endung, endungBekannt, true,
                    erwartet, 0);
        }

        Format treffer = null;
        Magie passende = null;
        for (Format format : TABELLE) {
            Magie mg = passendeMagie(kopf, format);
            if (mg != null) {
                treffer = format;
                passende = mg;
                break;
            }
        }

        if (treffer == null) {
            return ergebnisOhneMagie(pfad, groesse, kopf, endung, erwartet, endungBekannt);
        }

        String id = treffer.id();
        String name = treffer.name();
        String oeffnen = treffer.openWith();
        String zipArt = null;

        String begruendung = (passende.offset() == 0
                ? "前 " + passende.bytes().length + " 字节是 " + hex(passende.bytes())
                : "偏移 " + passende.offset() + " 处的 " + passende.bytes().length
                        + " 字节是 " + hex(passende.bytes()));

        // 魔数只是"它是 ZIP"，还得再往里看一层才知道是 docx 还是 jar。
        if ("zip".equals(id)) {
            ZipArt art = zipFamilie(pfad);
            if (art != null) {
                zipArt = art.id();
                id = art.id();
                name = art.name();
                oeffnen = art.openWith();
                begruendung += "；" + art.hinweis();
            }
        }

        // MP4 家族里还藏着 HEIC / MOV，用 ftyp 后面的 brand 再细分一次。
        if ("mp4".equals(id)) {
            String brand = isoBrand(kopf);
            if (brand != null) {
                switch (brand) {
                    case "heic", "heix", "hevc", "mif1", "msf1" -> {
                        id = "heic";
                        name = "HEIC 图片";
                        oeffnen = "Windows 照片 / 在线转换工具";
                        begruendung += "；品牌标识是 " + brand;
                    }
                    case "qt  " -> {
                        id = "mov";
                        name = "QuickTime 视频 (MOV)";
                        oeffnen = "VLC / 媒体播放器";
                        begruendung += "；品牌标识是 qt";
                    }
                    default -> {
                        // 保持 MP4
                    }
                }
            }
        }

        boolean endungPasst = passtZurEndung(id, erwartet);
        int exitCode = 0;

        return new Ergebnis(pfad, groesse, kopf, id, name, begruendung, oeffnen,
                zipArt, endung, endungBekannt, endungPasst, erwartet, exitCode);
    }

    private static Ergebnis ergebnisOhneMagie(
            Path pfad, long groesse, byte[] kopf, String endung,
            String erwartet, boolean endungBekannt) throws IOException {

        TextBefund text = pruefeText(pfad);
        boolean endungPasst = !endungBekannt || "text".equals(erwartet);

        if (text.istText()) {
            String hinweis = text.hinweis();
            if (text.encoding() != null) {
                hinweis += "，编码判定：" + text.encoding();
            }
            return new Ergebnis(pfad, groesse, kopf, "text", "纯文本", hinweis,
                    "记事本 / VS Code", null, endung, endungBekannt, endungPasst,
                    erwartet, 0);
        }

        return new Ergebnis(pfad, groesse, kopf, "unknown", "认不出来",
                text.hinweis(), "—", null, endung, endungBekannt, false,
                erwartet, 1);
    }

    /** 只有"期望的类型"和"实际类型"一致时才算对。没有扩展名就不下结论。 */
    static boolean passtZurEndung(String id, String erwartet) {
        if (erwartet == null) {
            return true;
        }
        return erwartet.equals(id)
                || ("text".equals(erwartet) && "text".equals(id))
                || ("mp4".equals(erwartet) && "mov".equals(id));
    }

    // ------------------------------------------------------------------
    // 字节层面的小工具
    // ------------------------------------------------------------------

    /** 只读开头 16 字节。注意 readNBytes 不会因为文件比 16 字节短而报错。 */
    static byte[] liesKopf(Path pfad) throws IOException {
        try (InputStream in = Files.newInputStream(pfad)) {
            return in.readNBytes(KOPF_BYTES);
        }
    }

    /** 找到表里第一条能匹配上的魔数；匹配不上返回 null。 */
    static Magie passendeMagie(byte[] kopf, Format format) {
        for (Magie mg : format.magics()) {
            if (kopf.length < mg.offset() + mg.bytes().length) {
                continue;
            }
            boolean alleGleich = true;
            for (int i = 0; i < mg.bytes().length; i++) {
                if (kopf[mg.offset() + i] != mg.bytes()[i]) {
                    alleGleich = false;
                    break;
                }
            }
            if (alleGleich) {
                return mg;
            }
        }
        return null;
    }

    /**
     * byte 在 Java 里是有符号的：0x89 读出来是 -119。
     * {@code & 0xFF} 把它变成 137，这样才和 0x89 对得上。
     * 这个函数就是全项目最重要的一行。
     */
    static int unsigned(byte b) {
        return b & 0xFF;
    }

    static String hex(byte[] daten) {
        StringBuilder sb = new StringBuilder(daten.length * 3);
        for (int i = 0; i < daten.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", unsigned(daten[i])));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // ZIP 家族：docx/xlsx/pptx/jar/apk/epub 都是 ZIP 换了张皮
    // ------------------------------------------------------------------

    static ZipArt zipFamilie(Path pfad) {
        Set<String> namen = new HashSet<>();
        String mimetype = null;

        try (ZipFile zip = new ZipFile(pfad.toFile())) {
            Enumeration<? extends ZipEntry> eintraege = zip.entries();
            int n = 0;
            while (eintraege.hasMoreElements() && n < 500) {
                ZipEntry eintrag = eintraege.nextElement();
                namen.add(eintrag.getName());
                n++;
                if ("mimetype".equals(eintrag.getName())
                        && eintrag.getSize() >= 0 && eintrag.getSize() < 200) {
                    try (InputStream in = zip.getInputStream(eintrag)) {
                        mimetype = new String(in.readNBytes(200), StandardCharsets.US_ASCII).trim();
                    }
                }
            }
        } catch (IOException ex) {
            return new ZipArt("zip", "ZIP 压缩包（目录可能损坏）",
                    "解压软件 / 资源管理器", "读不出条目列表：" + ex.getMessage());
        }

        // 顺序有讲究：APK 也带 META-INF/MANIFEST.MF，所以先判 AndroidManifest。
        if (namen.contains("AndroidManifest.xml")) {
            return new ZipArt("apk", "Android 应用包 (APK)", "传到安卓手机安装",
                    "条目里有 AndroidManifest.xml");
        }
        if (hatPrefix(namen, "word/")) {
            return new ZipArt("docx", "Word 文档 (DOCX)", "Word / WPS / LibreOffice",
                    "条目里有 word/，所以它不是普通 ZIP，而是 Word 文档");
        }
        if (hatPrefix(namen, "xl/")) {
            return new ZipArt("xlsx", "Excel 表格 (XLSX)", "Excel / WPS / LibreOffice",
                    "条目里有 xl/，所以它是 Excel 表格");
        }
        if (hatPrefix(namen, "ppt/")) {
            return new ZipArt("pptx", "PowerPoint 演示文稿 (PPTX)", "PowerPoint / WPS",
                    "条目里有 ppt/，所以它是演示文稿");
        }
        if (namen.contains("META-INF/MANIFEST.MF")) {
            return new ZipArt("jar", "Java 程序包 (JAR)", "java -jar 运行",
                    "条目里有 META-INF/MANIFEST.MF");
        }
        if (mimetype != null) {
            switch (mimetype) {
                case "application/vnd.oasis.opendocument.text" ->
                        { return new ZipArt("odt", "OpenDocument 文本文档 (ODT)",
                                "LibreOffice / WPS", "mimetype 是 odt"); }
                case "application/vnd.oasis.opendocument.spreadsheet" ->
                        { return new ZipArt("ods", "OpenDocument 表格 (ODS)",
                                "LibreOffice", "mimetype 是 ods"); }
                case "application/vnd.oasis.opendocument.presentation" ->
                        { return new ZipArt("odp", "OpenDocument 演示文稿 (ODP)",
                                "LibreOffice", "mimetype 是 odp"); }
                case "application/epub+zip" ->
                        { return new ZipArt("epub", "EPUB 电子书", "Calibre / 阅读器",
                                "mimetype 是 epub+zip"); }
                default -> {
                    // 继续走普通 ZIP
                }
            }
        }

        return new ZipArt("zip", "ZIP 压缩包", "解压软件 / 资源管理器",
                "是普通压缩包（没有 word/ xl/ ppt/ 等特征目录）");
    }

    static boolean hatPrefix(Set<String> namen, String prefix) {
        for (String name : namen) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** MP4 的 ftyp 后面 4 个字节是品牌标识，例如 isom / mp42 / heic / qt。 */
    static String isoBrand(byte[] kopf) {
        if (kopf.length < 12) {
            return null;
        }
        byte[] brand = new byte[4];
        System.arraycopy(kopf, 8, brand, 0, 4);
        return new String(brand, StandardCharsets.US_ASCII);
    }

    // ------------------------------------------------------------------
    // 文本判定：认不出魔数时，至少告诉你"这像不像文字"
    // ------------------------------------------------------------------

    static TextBefund pruefeText(Path pfad) throws IOException {
        byte[] probe;
        try (InputStream in = Files.newInputStream(pfad)) {
            probe = in.readNBytes(TEXT_PROBE);
        }

        if (probe.length == 0) {
            return new TextBefund(true, "空文件", "文件是空的");
        }

        // BOM 是"开头就告诉你怎么读"的标记，比猜更可靠。
        if (probe.length >= 3 && unsigned(probe[0]) == 0xEF
                && unsigned(probe[1]) == 0xBB && unsigned(probe[2]) == 0xBF) {
            return new TextBefund(true, "UTF-8（带 BOM）", "开头是 EF BB BF");
        }
        if (probe.length >= 2 && unsigned(probe[0]) == 0xFF && unsigned(probe[1]) == 0xFE) {
            return new TextBefund(true, "UTF-16LE", "开头是 FF FE（UTF-16LE BOM）");
        }
        if (probe.length >= 2 && unsigned(probe[0]) == 0xFE && unsigned(probe[1]) == 0xFF) {
            return new TextBefund(true, "UTF-16BE", "开头是 FE FF（UTF-16BE BOM）");
        }

        // 二进制文件里常有 0x00；正常文本（UTF-8/GBK）不会。
        for (byte b : probe) {
            if (b == 0) {
                return new TextBefund(false, null, "样本里出现 0x00 字节，是二进制特征");
            }
        }

        if (dekodierbar(probe, StandardCharsets.UTF_8)) {
            return new TextBefund(true, "UTF-8", "按 UTF-8 严格解码成功");
        }
        if (dekodierbar(probe, Charset.forName("GB18030"))) {
            return new TextBefund(true, "GB18030", "按 GB18030 严格解码成功，中文文本常见");
        }

        int druckbar = 0;
        for (byte b : probe) {
            int c = unsigned(b);
            if (c == 9 || c == 10 || c == 13 || (c >= 32 && c < 127)) {
                druckbar++;
            }
        }
        if (druckbar * 100L / probe.length >= 95) {
            return new TextBefund(true, "ASCII", "95% 以上是可打印 ASCII 字符");
        }

        return new TextBefund(false, null, "既不是已知格式，也不像文本");
    }

    /**
     * 严格解码：只要有一个字节不合法就返回 false。
     * 末尾最多切掉 3 个字节，防止采样正好截断一个多字节字符。
     */
    static boolean dekodierbar(byte[] daten, Charset charset) {
        for (int cut = 0; cut <= 3 && cut < daten.length; cut++) {
            ByteBuffer bb = ByteBuffer.wrap(daten, 0, daten.length - cut);
            try {
                charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(bb);
                return true;
            } catch (CharacterCodingException ex) {
                // 继续试少一个字节
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 输出
    // ------------------------------------------------------------------

    static void drucke(Ergebnis e) {
        System.out.println("文件    " + e.pfad());
        System.out.println("大小    " + menschlich(e.groesse()));
        System.out.println("头部    " + (e.kopf().length == 0 ? "（空）" : hex(e.kopf())));
        System.out.println();
        System.out.println("结果    " + e.typName());
        System.out.println("依据    " + e.begruendung());
        System.out.println("打开    " + e.oeffnen());

        if (e.endungBekannt() && !e.endungPasst()) {
            System.out.println("[!]     扩展名 ." + e.endung() + " 与内容不符：." + e.endung()
                    + " 通常是 " + typNameVon(e.erwartet()) + "，但实际是 " + e.typName());
        }
        System.out.println();
    }

    static String typNameVon(String id) {
        if (id == null) {
            return "未知";
        }
        for (Format f : TABELLE) {
            if (f.id().equals(id)) {
                return f.name();
            }
        }
        return switch (id) {
            case "docx" -> "Word 文档 (DOCX)";
            case "xlsx" -> "Excel 表格 (XLSX)";
            case "pptx" -> "PowerPoint 演示文稿 (PPTX)";
            case "jar" -> "Java 程序包 (JAR)";
            case "apk" -> "Android 应用包 (APK)";
            case "odt" -> "OpenDocument 文本文档 (ODT)";
            case "ods" -> "OpenDocument 表格 (ODS)";
            case "odp" -> "OpenDocument 演示文稿 (ODP)";
            case "epub" -> "EPUB 电子书";
            case "text" -> "纯文本";
            case "empty" -> "空文件";
            default -> "未知";
        };
    }

    static String menschlich(long bytes) {
        if (bytes < 1024) {
            return bytes + " 字节";
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f KB（%,d 字节）", bytes / 1024.0, bytes);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB（%,d 字节）",
                    bytes / (1024.0 * 1024), bytes);
        }
        return String.format(Locale.ROOT, "%.2f GB（%,d 字节）",
                bytes / (1024.0 * 1024 * 1024), bytes);
    }

    static String endungVon(Path pfad) {
        String name = pfad.getFileName().toString();
        int punkt = name.lastIndexOf('.');
        if (punkt <= 0 || punkt == name.length() - 1) {
            return null;
        }
        return name.substring(punkt + 1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // JSON：给别的程序调用（--json），手写是为了不引入依赖
    // ------------------------------------------------------------------

    static void druckeJson(List<Ergebnis> alle) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < alle.size(); i++) {
            Ergebnis e = alle.get(i);
            sb.append("  {");
            sb.append("\"path\":").append(jsonText(e.pfad().toString())).append(',');
            sb.append("\"size\":").append(e.groesse()).append(',');
            sb.append("\"head\":").append(jsonText(hex(e.kopf()))).append(',');
            sb.append("\"type\":").append(jsonText(e.id())).append(',');
            sb.append("\"typeName\":").append(jsonText(e.typName())).append(',');
            sb.append("\"evidence\":").append(jsonText(e.begruendung())).append(',');
            sb.append("\"openWith\":").append(jsonText(e.oeffnen())).append(',');
            sb.append("\"extension\":").append(jsonText(e.endung())).append(',');
            sb.append("\"extensionMatches\":").append(e.endungPasst()).append(',');
            sb.append("\"expected\":").append(jsonText(e.erwartet())).append(',');
            sb.append("\"exitCode\":").append(e.exitCode());
            sb.append('}');
            if (i < alle.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("]\n");
        System.out.print(sb);
    }

    static String jsonText(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 构造表项的小助手，让上面的表看起来干净
    // ------------------------------------------------------------------

    static Magie m(int offset, int... werte) {
        byte[] bytes = new byte[werte.length];
        for (int i = 0; i < werte.length; i++) {
            bytes[i] = (byte) werte[i];
        }
        return new Magie(offset, bytes);
    }

    static Format f(String id, String name, String openWith, Magie... magics) {
        return new Format(id, name, openWith, List.of(magics));
    }
}
