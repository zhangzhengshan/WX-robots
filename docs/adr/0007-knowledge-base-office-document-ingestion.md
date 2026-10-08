# 知识库摄取 Word/Excel：Apache POI + 目录同步

2026-09-15 P3 设计拷问锁定。扩展 scope 功能 #4：知识库素材从「仅 Markdown/纯文本」增加 **.docx / .xlsx**；客户实际素材为 1 个 docx + 2 个 xlsx。

## Decision

知识库事实源是 `data/kb/` 目录，`kb_document` 表是其纯文本索引：

- 支持格式：`.docx`、`.xlsx`（Apache POI 5.5.1，jar 放 `bot/lib/`，遵循 ADR-0004 零构建系统）、`.txt`、`.md`（UTF-8 直读）。旧版 `.doc/.xls` 明确报错，提示另存为新格式。
- docx：POI `XWPFWordExtractor` 提取正文段落与表格；xlsx：逐 sheet 输出「## sheet 名」标题，行内单元格用 ` | ` 连接，全空行跳过，空 sheet 不输出，公式取计算值。
- 标题 = 文件名去掉扩展名；启动时自动同步一次，另有 `KbImport` 手动入口（不重启重导）。同步在事务语义内做 upsert/删除 diff：文件从目录消失则删除对应行；解析失败的文件跳过并保留库内旧行。
- 同标题不同扩展名冲突时只收按文件名排序的第一个；转换后全库总字数仍守 10 万硬上限，超限的单篇跳过、其余继续。
- POI 日志经 log4j-to-slf4j 桥接到现有 slf4j-simple（slf4j 升级到 2.0.x），不引入第二套日志实现；POI 的可选依赖（xmlsec/bouncycastle/pdfbox/batik，签名/PDF/SVG 用）一律不下载。
- P5 管理端边界：只做「上传文件 + 文件列表删除」，不做在线内容编辑；文件改一个字也必须在 WPS/Office 改好后重新上传，并先在后台删除旧文件（同名覆盖的后续取舍留给 P5）。

## Alternatives Considered

- **JDK 自带 zip+JAXP 手写解析（零新 jar）**：docx/xlsx 就是 zip+XML，可行但表格/共享字符串/公式细节多，维护成本高。用户明确选择引入 POI，否决。
- **Apache POI 全量依赖（含 scratchpad/可选模块）**：覆盖 .doc/.xls 与 PDF 导出等，但多十几个 jar、攻击面更大；只需新格式纯文本，取最小 compile 依赖集合即可。
- **管理端直接编辑知识库正文（scope 原设想的 Markdown 编辑）**：与客户「素材就是 Office 文件、只接受重新上传」的工作方式冲突，且在线编辑器属于额外复杂度。否决。

## Consequences

- 优势：客户无需转换文件格式，丢进目录即生效；POI 对表格/样式解析稳健，抽取逻辑不到 100 行。
- 代价：引入约 18MB 的第三方 jar（POI/xmlbeans/commons 系列）；xlsx 转纯文本丢失合并单元格语义与样式，复杂排版可能影响模型阅读（当前素材是价格表/操作流程类结构化表格，可接受）。
- 约束：只抽取顾客可见文本，提示词层另行约束「不输出内部 SOP/成本/责任人」等字段；10 万字上限仍是全量进提示词方案的硬边界（ADR-0003）。
