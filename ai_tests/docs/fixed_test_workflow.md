# Android Debug 设备验证流程

本流程适用于 Mac 上的 AI 听书 Debug 验收，不替代正式发布链。

1. 确认目标包为 `io.legado.miss.app.debug`、设备为指定测试 AVD/设备。不得操作正式包或清除用户数据。
2. 配置 Java 21 与 Android SDK；通过 `adb devices -l` 确认设备在线。
3. 先执行 JVM 定向回归，再执行全量 `:app:testAppDebugUnitTest`。
4. 构建主 APK 和测试 APK，使用 instrumentation runner 参数筛选设备用例：

   ```sh
   sh ./gradlew :app:assembleAppDebug :app:connectedAppDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.NovelAudioMigrationTest,io.legado.app.NovelAudioRecoveryDeviceTest,io.legado.app.NovelAudioServerCredentialsDeviceTest
   ```

   `--tests` 仅用于 JVM Test 任务。不得用于 `connectedAppDebugAndroidTest`。

5. 检查实际执行数量、XML/HTML 报告与退出码。初始化异常不算业务断言已验证，0 个用例不算成功。
6. 再验证用户入口、当前阅读位置、后台/媒体键、三章离线、缺段失败、杀进程恢复与普通朗读。
7. 云模型调用遵守已授权次数和额度；协议 Mock 与录制音频测试必须明确标注，不作为音质或真实文本合成证据。
8. 运行适用交付门禁，保存 APK 的包名、版本、校验值和测试记录。未通过时只记录阻断，不交付为可用版本。

数据库和凭据用例使用独立文件/alias。飞行模式或权限测试需要记录并恢复测试设备原状态。
测试报告默认在 `app/build/reports/androidTests/connected/`，该构建目录不是长期交付存档。
