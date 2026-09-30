# 数据库迁移安全

本规范按当前 Android 工程补建；本阶段交付口径见 `../AI_AUDIOBOOK_ANDROID_DELIVERY_RULES.md`。

- `AppDatabase.kt` 是目标版本的事实源；历史 schema JSON 不得为通过测试而修改。
- 新表使用显式 migration，并注册到生产数据库构造路径；禁止为修复升级失败增加 destructive fallback。
- 迁移测试从历史 schema 建库，插入书籍、阅读进度、人物及旧朗读缓存，再通过生产 Room 配置打开。
- 同时断言新表结构及旧记录内容；仅检查新表存在不能证明旧数据保留。
- 异步写入和失效更新必须在同一事务内验证计划 generation。旧请求不得更新新 artifact 或下载任务。
- 每个设备测试使用独立数据库名称或 in-memory database，失败也关闭连接；不得删除 `legado.db`。

提交与交付前，涉及的 Room instrumentation 必须实跑并保存测试数量、失败堆栈和退出码。
编译成功、JVM SQL 模拟或运行时初始化失败均不能替代迁移验收。

现有入口：`NovelAudioMigrationTest`（迁移与查询）、`NovelAudioRecoveryDeviceTest`（恢复与隔离）。
