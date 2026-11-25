# 样例文件与期望输出

这 7 个文件都是**自制的、极小的**（最大 632 B），全部由脚本生成，可以随便改、随便删。

| 文件 | 大小 | 开头 6 字节 | M1 应报 | M2 额外应报 | M3 额外应报 |
|---|---|---|---|---|---|
| `beispiel.pdf` | 193 B | `25 50 44 46 2D 31` | **PDF** | 用浏览器打开 | — |
| `beispiel.png` | 69 B | `89 50 4E 47 0D 0A` | **PNG** | 用照片查看器 | — |
| `beispiel.jpg` | 632 B | `FF D8 FF E0 00 10` | **JPEG** | 用照片查看器 | — |
| `beispiel.zip` | 131 B | `50 4B 03 04 14 00` | **ZIP** | 解压 / 资源管理器 | **ZIP 压缩包**（里面没有 word/） |
| `beispiel.docx` | 313 B | `50 4B 03 04 14 00` | **ZIP** | — | **Word 文档**（含 `word/`） |
| `fake.jpg` | 69 B | `89 50 4E 47 0D 0A` | **PNG** | ⚠️ **扩展名与内容不符** | — |
| `beispiel.txt` | 41 B | `44 61 73 20 69 73` | 认不出来 | 提示"可能是文本" | **纯文本** |

## 怎么用

```bash
# 一条一条跑，对照上表
java FileDetective samples/beispiel.pdf
java FileDetective samples/beispiel.png
java FileDetective samples/beispiel.jpg
java FileDetective samples/beispiel.zip
java FileDetective samples/beispiel.docx
java FileDetective samples/fake.jpg
java FileDetective samples/beispiel.txt
```

## 追加：真实世界的测试文件

自制样例只验证"逻辑对不对"。真实文件还要另测（本机现成的）：

```bash
java FileDetective "D:\LMU-CL-Kurse\EIP\EIP.pdf"
java FileDetective "D:\LMU-CL-Kurse\01_Einfuehrung_in_die_Computerlinguistik_WS2627\01 - Überblick und Grundkonzepte\WordNet_bat.png"
java FileDetective "D:\LMU-CL-Kurse\02_Einfuehrung_in_die_Programmierung_WS2627\Vorlesungsmaterialien\05_Praktikum 1.zip"
java FileDetective "D:\LMU-CL-Kurse\01_Einfuehrung_in_die_Computerlinguistik_WS2627\05 - Konstituenz und Dependenz\Analysebaum_1.jpg"
```

## 生成方式（可复现）

这些文件是 Python 生成的：PDF 手写最小结构、PNG/JPEG 用 Pillow 存 1×1 像素、ZIP 用 `zipfile`、`fake.jpg` 是 PNG 字节改了扩展名。不依赖任何外部素材，也就没有版权问题。
