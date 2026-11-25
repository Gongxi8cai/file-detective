# file-detective

**不看扩展名，只看文件开头那 16 个字节，判断它到底是什么文件，并告诉你该怎么打开。**

[![build](https://github.com/Gongxi8cai/file-detective/actions/workflows/build.yml/badge.svg)](https://github.com/Gongxi8cai/file-detective/actions/workflows/build.yml)

> 状态：**v1.0**（M1–M4 完成）。纯 Java、零依赖，`javac` 编译，`java` 运行。

## 它解决什么问题

双击 `foto.jpg` 打不开？十有八九它**根本不是** JPG —— 扩展名只是文件名的一部分，谁都能改。真身藏在文件头里：

| 你看到的名字 | 开头字节 | 真实身份 |
|---|---|---|
| `foto.jpg` | `89 50 4E 47` | 其实是 PNG |
| `protokoll.doc` | `50 4B 03 04` | 其实是 docx（ZIP 马甲） |
| `setup.exe` | `7F 45 4C 46` | 其实是 Linux 程序 |
| `invoice.jpg` | `4D 5A` | 其实是 Windows 可执行文件 |

`file-detective` 就是 `file` 命令 / `binwalk` 的玩具版：读魔数、查表、给出结论，再顺手检查扩展名有没有撒谎。

## 快速开始

需要 **JDK 21+**（[Temurin](https://adoptium.net/) 或任意发行版）。

```bash
# 方式一：单文件源码直接跑（最简单，Java 11+）
java FileDetective.java samples/beispiel.pdf

# 方式二：先编译
javac --release 21 -encoding UTF-8 -d out FileDetective.java
java -cp out FileDetective samples/beispiel.pdf
```

一次可以传多个文件；加 `--json` 给脚本用：

```bash
java FileDetective samples/fake.jpg samples/beispiel.docx
java FileDetective --json samples/fake.jpg
```

## 输出长什么样

```
文件    samples\fake.jpg
大小    69 字节
头部    89 50 4E 47 0D 0A 1A 0A 00 00 00 0D 49 48 44 52

结果    PNG 图片
依据    前 8 字节是 89 50 4E 47 0D 0A 1A 0A
打开    照片查看器 / 浏览器
[!]     扩展名 .jpg 与内容不符：.jpg 通常是 JPEG 图片，但实际是 PNG 图片
```

`--json` 输出一个数组，每个元素包含 `type`、`typeName`、`extensionMatches`、`exitCode` 等字段。

## 支持的格式

| 类别 | 格式 |
|---|---|
| 文档 | PDF、ZIP 家族（DOCX / XLSX / PPTX / ODT / ODS / ODP / EPUB / JAR / APK）、旧版 OLE 文档 |
| 图片 | PNG、JPEG、GIF、BMP、TIFF、WebP、ICO、HEIC |
| 压缩 | ZIP、GZIP、7-Zip、RAR |
| 音视频 | MP3、OGG、FLAC、WAV、AVI、MP4、MOV、MKV / WebM |
| 程序与数据 | ELF、Windows EXE/DLL、SQLite、Java `.class`、WebAssembly |
| 字体 | TTF、WOFF2 |
| 文本 | UTF-8 / GB18030 / ASCII 纯文本（启发式判断） |

**ZIP 家族**是个重点：`.docx`、`.xlsx`、`.jar`、`.apk` 全都是 ZIP，只是里面装的东西不同。程序会打开压缩包看条目：有 `word/` 就是 Word，有 `AndroidManifest.xml` 就是 APK。

## 退出码

| 退出码 | 含义 |
|---|---|
| `0` | 认出来了（包括纯文本） |
| `1` | 认不出来 |
| `2` | 参数错误 / 文件不存在 / 不是普通文件 |

方便在脚本里串联：`java FileDetective "$f" || echo "未知文件: $f"`。

## 它是怎么工作的（三条，也是三个知识点）

1. **只读开头 16 个字节。** 判断类型不需要读完整个文件，一个 4 GB 的视频也只读 16 字节。代码里用 `InputStream.readNBytes(16)`，不是 `readAllBytes()`。
2. **用一张表做判定。** 所有魔数都在 `TABELLE` 里，加一种格式只需要加一行 `f(...)`。这叫表驱动，比一长串 `if-else` 好维护。
3. **`byte` 是有符号的。** Java 里 `0x89` 存进 `byte` 是 `-119`，直接和 `0x89` 比较永远不相等，必须先 `b & 0xFF`。这就是 `unsigned()` 那一行——全项目最重要的地基。

### 亲手验证第 3 条（强烈建议）

把 `FileDetective.java` 里 `unsigned()` 的 `& 0xFF` 删掉，改成 `return b;`，重新编译，再跑一次 PNG 样例：

```java
static int unsigned(byte b) {
    return b;   // 故意写错：0x89 会变成 -119
}
```

你会发现 PNG 认不出来了——因为 `-119 != 0x89`。**症状出现在很远的下游，根因在类型转换**，这是新手最常踩的坑。

## 样例与测试

`samples/` 里有 7 个自制的小文件（最大 632 B），每个的期望输出写在 [`samples/ERWARTET.md`](samples/ERWARTET.md)。

```bash
javac --release 21 -encoding UTF-8 -d out FileDetective.java test/FileDetectiveTest.java
java -cp out FileDetectiveTest
```

测试不依赖 JUnit——clone 下来两条命令就能跑，这是刻意的。

## 项目结构

```
file-detective/
├── FileDetective.java              # 全部实现（单文件，约 760 行）
├── test/FileDetectiveTest.java     # 25 条断言，纯 Java
├── samples/                        # 7 个自制样例 + 期望输出
├── docs/                           # 项目拆分、知识路径图
└── .github/workflows/build.yml     # push 后自动编译 + 测试
```

## 路线图

| 版本 | 内容 | 状态 |
|---|---|---|
| M1 v0.1 | 读文件头 + 魔数表 + 类型判定 | ✅ |
| M2 v0.2 | 扩展名比对 + 打开建议 + 退出码 | ✅ |
| M3 v0.3 | ZIP 家族细分 + 文本/二进制判定 | ✅ |
| M4 v1.0 | README、样例、测试、Actions | ✅ |
| 下一步 | 扫描整个目录出统计、损坏检测（PDF `%%EOF` / PNG `IEND`）、和 [byte-xray](https://github.com/Gongxi8cai/byte-xray) 联动 | ⏳ |

## 已知限制

- 只认文件头，不校验文件完整性；一个被截断的 PNG 仍然会报 PNG。
- ZIP 家族靠条目名判断，理论上可以伪造；这是玩具，不是安全工具。
- 文本判定是启发式：无 BOM 的 UTF-16 会被当成二进制，随机字节偶尔会被误判为 GB18030 文本。
- 不递归扫描目录（还没做）。

## 许可

MIT，见 [LICENSE](LICENSE)。
