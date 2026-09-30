# Room MigrationTestHelper loads schema bundles and kotlinx.serialization types
# indirectly after the minified debug test APK is installed.
-keep class androidx.room.migration.bundle.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep interface kotlinx.serialization.** { *; }
