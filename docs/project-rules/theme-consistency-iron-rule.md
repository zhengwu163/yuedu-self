# 主题一致性：静态门禁的实际覆盖范围

本文件补充 `docs/AI_AUDIOBOOK_ANDROID_DELIVERY_RULES.md` 第 3、4 节与
`docs/project-rules/testing-iron-rule.md`，记录主题脚本可验证的范围。
K1 取色来源核对、K2 适用门禁、K3 默认/自定义主题色/主题包/夜间设备验证、
K4 失败自检仍需分别完成；单个 Python 脚本通过不能替代这些检查。

## 1. 文件发现与失败处理

`ai_tests/scripts/audit_theme_token_violation.py --base HEAD`：

- `--base` 必须解析为单一提交（可使用提交哈希、分支、标签或 `HEAD~1`），默认 `HEAD`。
- 检查该提交到当前工作树的净差异，覆盖已暂存和未暂存内容，并合并非忽略的未跟踪文件。
- 保留原有范围：路径包含 `/ui/`、扩展名为 `.kt`、`.java` 或 `.xml`，且工作树中仍存在的文件。
- 删除文件、未改动历史文件、非 UI 文件和被 Git 忽略的未跟踪文件不进入扫描。
- 使用 NUL 分隔的文件名，支持中文、空格和换行；同一文件去重。
- 审计工作树内容，不是单独审计暂存区快照。暂存内容若已在工作树恢复为基线，则没有净内容变化。
- 基线解析、差异查询、未跟踪列表或选区提示的行差异查询任一步 Git 失败，退出码为 `2`，不得报通过。
  无 Git、非仓库、无首个提交的 `HEAD` 同样不能按“无变化”处理。

## 2. 保留的阻断规则

对发现的变更文件全文执行既有三个匹配规则：

1. `Color(0x...)`
2. `Color.rgb(...)` / `Color.argb(...)`
3. `setBackgroundColor(...)`

匹配仍是文本级检查，包括变更文件中的历史内容；不转成只检查新增行，也不扩大到所有历史文件。
保留原有 allowlist 的 `paths` 读取契约；本次修复不增加条目或新豁免。
这些规则并不覆盖全部 Android/Compose 取色方式，不能证明 token 来源、对比度或主题刷新正确。

## 3. 文本框选区颜色：聚焦人工复核提示

选区检查只产生 **advisory**，不是可靠的“缺失选区颜色”阻断判定。
它对变更 Kotlin 文件中的直接 `OutlinedTextField(...)` / `BasicTextField(...)` 调用：

- 屏蔽普通字符串、原始字符串、字符字面量及注释，平衡括号并拆分普通命名参数。
- 识别内联 `OutlinedTextFieldDefaults` / `TextFieldDefaults` 的 `colors` / `outlinedTextFieldColors`
  调用中明确传入的 `*Color` 参数，以及直接 `cursorBrush`、`TextStyle(color = ...)`、
  `textStyle = ...copy(color = ...)`。
- 若颜色工厂内没有非 `null` 的 `selectionColors` 参数，则列为待复核候选。
- 已跟踪文件仅提示与零上下文差异行相交的调用，包括删除参数的情况；
  未跟踪文件中的调用视为新增。只改文件其他位置不会触发历史调用提示。
- 输出文件与行号，并明确声明 `LocalTextSelectionColors` 作用域/继承 **未验证**。
  原有颜色 allowlist 不会屏蔽这项人工复核提示。

**必须人工或由 Kotlin/Compose 工具补充验证的范围：**

- `LocalTextSelectionColors` 可从调用方、主题包装器或其他文件动态继承；
  同文件出现名称、import 或相邻 provider 都不能证明覆盖了目标控件。
  即使同文件确有有效 provider，本脚本也可能要求人工复核，不会据此阻断。
- 提供 `selectionColors` 仅证明参数语法存在，不证明值、作用域或四态效果正确。
- 不解析 import alias、自定义同名函数、跨函数颜色工厂、变量传递、位置参数、
  复杂泛型参数与字符串模板中的 Kotlin 表达式；本检查不是 Kotlin AST/类型分析。
- 不追踪调用体外颜色变量或 provider 的变化；仅修改外层 provider、共享样式或调用者时可能没有提示。
- `/ui/` 之外的组件不在既有扫描范围内。无提示不代表选区颜色正确。

## 4. 自检与退出码

macOS 自检命令：

```sh
ai_tests/venv/bin/python -B scripts/test_theme_token_audit.py -v
```

自检通过真实临时 Git 仓库运行脚本 CLI；提交、暂存、重命名和故障注入仅发生在临时夹具，
不修改项目索引、不调用项目 hooks、不读取真实环境凭据，也不运行 Gradle、设备或云调用。

- `0`：上述硬编码颜色规则未发现阻断；选区颜色仍未验证，可能伴随 advisory。
- `1`：发现未登记的硬编码颜色匹配。
- `2`：Git、文件读取或 allowlist 读取/JSON 解析失败。

此自检不能作为其他门禁、Android 测试、四态截图或产品验收通过的证据。
