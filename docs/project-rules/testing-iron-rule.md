# 代码与测试配对

- 每个生产变更必须有对应行为测试。修复先保留失败复现，再运行修复后的同一用例。
- 纯逻辑用 JVM 测试；Room SQL、Android Keystore、媒体解码与系统服务必须追加设备测试。
- 构建脚本的动态调用需执行实际任务，不能仅靠源码字符串匹配。Cronet 对应 `scripts/test_cronet_preparation.py`。
- 先跑定向用例，再跑 `:app:testAppDebugUnitTest`。跳过数单列，不计入通过数。
- 测试自检须证明故障会导致失败。缺失检查或空检查阶段不得报告成功。
- `ai_tests/scripts/run_gates.py --stage deliver` 是当前 Debug 静态门禁入口，不能代替单测、设备验收或实际 APK 内容审计。
- 未配对、未执行、失败与环境阻断必须保持未完成；不得删除断言、扩大豁免或跳过 hook 来交付。

macOS 使用 `ai_tests/venv/bin/python`；Windows 使用 `ai_tests/venv/Scripts/python.exe`。
本地源码与 APK 产物分别验证，测试结果写入项目进度记录，不写入用户更新日志。
