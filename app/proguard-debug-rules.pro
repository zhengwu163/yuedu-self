# proguard-debug-rules.pro（2026-09-15 双包优化：测试包对齐正式包裁剪深度）
# debug proguardFiles 已换 proguard-android-optimize.txt（与 release 同款优化器，实测砍掉 14.2MB dex）
# 本文件仅保留混淆红线：历史 R8 混淆重命名导致正式包大量未知错误（用户裁决），debug 同样禁止混淆
# 调试日志铁律：BuildConfig.DEBUG=true 分支可达，DebugLog/AppLog 调用与字符串由 R8 可达性天然保留，禁止裁剪
-dontobfuscate

# androidTest/instrumented 铁律（2026-09-22 新增，铁证）：
# 测试进程内运行的第三方库（androidx.test 等，来自测试 APK）会解析 kotlin 标准库的**非内联**入口，
# 而应用自身对 `lazy{}` 等用法是**编译期内联**⇒ R8 按「未被静态引用」把 `kotlin.LazyKt` 等类从 APK 移除，
# 导致 instrumented 启动即崩：`NoClassDefFoundError: Failed resolution of: Lkotlin/LazyKt;`
#   at androidx.test.platform.io.TestDirCalculator.<init>(TestDirCalculator.kt:30)
# ⇒ debug（测试包）必须整体保留 kotlin 标准库；release 不受影响（正式包无 instrumented 需求）。
-keep class kotlin.** { *; }
-dontwarn kotlin.**

# Instrumentation shares serialization core with the app (via AndroidX).
# AGP excludes shared dependencies from the test APK, so the minified host
# must retain the core API and serializers needed by Room MigrationTestHelper.
# Test-only keep rules cannot retain classes owned by the host APK.
-keep class kotlinx.serialization.** { *; }

# Compose instrumentation shares coroutines-core with the host, but its test
# scheduler subclasses dispatchers and invokes internal core APIs from the test APK.
# Preserve that library boundary in debug (as for Kotlin above); release is unchanged.
-keep class kotlinx.coroutines.** { *; }

# UI instrumentation traverses host semantics and implements host Compose interfaces.
# Those cross-APK calls (RootForTest, InfiniteAnimationPolicy, selection/layout APIs)
# are invisible to host R8. Keep Compose's shared runtime boundary for device tests.
-keep class androidx.compose.** { *; }
# Compose compiler metadata is read by separately compiled test composables.
-keepclassmembers class io.legado.app.** {
    public static final int $stable;
}

# MigrationTestHelper subclasses this host-owned base from the test APK.
# Host-only shrinking cannot see those subclasses and can replace an inherited
# default implementation (resolveFileName) with an abstract method.
-keep class androidx.room.BaseRoomConnectionManager { *; }

# Host APIs exercised by isolated device databases and credential tests.
# Their callers live in the test APK and are invisible to host-only shrinking.
-keep class androidx.room.Room { *; }
-keep class androidx.room.RoomDatabase { *; }
-keep class androidx.room.RoomDatabase$* { *; }
# Historical-schema fixtures use the parameterized SQL overload from the test APK.
-keep interface androidx.sqlite.db.SupportSQLiteDatabase {
    public void execSQL(java.lang.String, java.lang.Object[]);
}
-keepclassmembers class androidx.sqlite.db.framework.FrameworkSQLiteDatabase {
    public void execSQL(java.lang.String, java.lang.Object[]);
}
-keep interface io.legado.app.data.dao.NovelAudioDao { *; }
-keep class io.legado.app.data.dao.NovelAudioDao_Impl { *; }
-keep class io.legado.app.help.readaloud.server.NovelAudioServerConfigStore { *; }
-keep class io.legado.app.help.readaloud.server.NovelAudioAndroidConfigStore { *; }
-keep class io.legado.app.help.readaloud.server.NovelAudioServerSettings { *; }
# The isolated Fragment subclass/factory is owned by androidTest.
-keep class io.legado.app.ui.book.read.config.NovelAudioServerConfigDialog { *; }
# The theme fixture audits the host's runtime-key contract from androidTest.
# Host R8 cannot see calls from the separate test APK, so retain this debug-only
# method without keeping the rest of the theme runtime surface.
-keep class io.legado.app.lib.theme.ThemeRuntimeKeys {
    public java.util.Set allKeys();
}
-keep class io.legado.app.lib.theme.ThemeStore$Companion {
    public void setAccentColor(int);
}
# Theme cleanup restores the isolated fixture's in-memory mode from the
# separate androidTest APK; host R8 cannot see these Kotlin object accessors.
-keep class io.legado.app.help.config.AppConfig {
    public java.lang.String getThemeMode();
    public void setThemeMode(java.lang.String);
    public boolean isEInkMode();
    public void setEInkMode(boolean);
}

# 【已证伪，勿再加】曾试 `-keep class j$.** { *; }` 想让 androidTest APK 保留脱糖合成类，
# 实测无效：`j$.**` 的成员裁剪发生在 **D8 脱糖阶段**（只生成"本 APK 代码用到的" `$default$*` 方法），
# 不受 R8 keep 规则约束 ⇒ 测试 APK 里的 `j$.util.concurrent.ConcurrentMap$-CC` 仍缺 `$default$computeIfAbsent`，
# 与 app 侧同名类在**同一进程内按类名抢先解析**时仍抛 NoSuchMethodError（详见 issues §18.6 追加记录）。
