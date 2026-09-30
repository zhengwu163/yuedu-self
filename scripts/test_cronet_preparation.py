"""Exercise the real Gradle Cronet tasks and validate their output byte-for-byte.

Run with the project venv and the same JAVA_HOME/Android SDK as the Android build.
No APK, device operation, or cloud request is performed.
"""

import hashlib
import subprocess
import unittest
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
JARS = (
    "cronet_api.jar",
    "cronet_impl_common_java.jar",
    "cronet_impl_native_java.jar",
    "cronet_impl_native_sentinel_java.jar",
    "cronet_impl_platform_java.jar",
    "cronet_shared_java.jar",
    "httpengine_native_provider_java.jar",
)


class CronetPreparationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # --rerun-tasks is essential: UP-TO-DATE cannot detect task closure dispatch errors.
        command = [
            "sh", "./gradlew", ":app:prepareCronetJars", ":app:adaptCronetLoader",
            "--rerun-tasks", "--configuration-cache", "--max-workers=2", "--console=plain",
        ]
        cls.runs = []
        for _ in range(2):
            result = subprocess.run(
                command, cwd=ROOT, capture_output=True, text=True, timeout=180, check=False
            )
            if result.returncode != 0:
                raise AssertionError(f"Cronet task execution failed:\n{result.stdout}\n{result.stderr}")
            cls.runs.append(result.stdout)

    def test_forced_execution_also_succeeds_from_configuration_cache(self):
        self.assertIn("Configuration cache entry reused", self.runs[1])
        for output in self.runs:
            self.assertIn("Cronet jars prepared: class-file version downgraded", output)
            self.assertIn("official native/JNI initialization unchanged", output)

    def test_preparation_changes_only_newer_class_version_headers(self):
        original = ROOT / "app/cronetlib"
        prepared = ROOT / "app/build/cronet-jars"
        self.assertEqual(
            "775d145e5f33fd6157078a574f788b850bda5a0e7195aa9ec93e67afe6f64127",
            hashlib.sha256((original / "cronet_impl_native_java.jar").read_bytes()).hexdigest(),
        )
        for name in JARS:
            with self.subTest(jar=name), ZipFile(original / name) as source, ZipFile(prepared / name) as result:
                self.assertEqual(source.namelist(), result.namelist())
                class_count = 0
                for entry in source.namelist():
                    expected = source.read(entry)
                    if entry.endswith(".class") and expected.startswith(b"\xca\xfe\xba\xbe"):
                        class_count += 1
                        if int.from_bytes(expected[6:8], "big") > 61:
                            expected = expected[:4] + b"\x00\x00\x00\x3d" + expected[8:]
                    self.assertEqual(expected, result.read(entry), entry)
                self.assertGreater(class_count, 0)


if __name__ == "__main__":
    unittest.main()
