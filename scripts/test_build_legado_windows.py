import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


@unittest.skipUnless(os.name == "nt", "native Windows batch execution")
class WindowsBuildConfigurationTest(unittest.TestCase):
    def test_script_uses_its_repository_and_caller_toolchain(self):
        with tempfile.TemporaryDirectory(prefix="legado build ") as directory:
            root = Path(directory)
            script = root / "build-legado.bat"
            shutil.copyfile(Path(__file__).resolve().parents[1] / script.name, script)
            capture = root / "invocation.txt"
            (root / "gradlew.bat").write_text(
                '@echo off\n'
                '> "%TEST_CAPTURE_PATH%" echo %CD%\n'
                '>> "%TEST_CAPTURE_PATH%" echo %JAVA_HOME%\n'
                '>> "%TEST_CAPTURE_PATH%" echo %ANDROID_HOME%\n'
                '>> "%TEST_CAPTURE_PATH%" echo %GRADLE_USER_HOME%\n'
                '>> "%TEST_CAPTURE_PATH%" echo %*\n'
                'exit /b 0\n',
                encoding="ascii",
            )
            expected = [str(root), str(root / "jdk"), str(root / "sdk"), str(root / "cache"), "clean"]
            environment = dict(os.environ)
            environment.update(
                JAVA_HOME=expected[1], ANDROID_HOME=expected[2],
                GRADLE_USER_HOME=expected[3], TEST_CAPTURE_PATH=str(capture),
            )
            environment.pop("PROJECT_DIR", None)
            command = f'"{os.environ.get("COMSPEC", "cmd.exe")}" /d /s /c ""{script}" clean"'
            result = subprocess.run(
                command, cwd=root.parent, env=environment, input=b"\n",
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20,
            )
            self.assertEqual(0, result.returncode, result.stderr.decode(errors="replace"))
            self.assertTrue(capture.is_file(), "batch script never reached this repository's Gradle wrapper")
            self.assertEqual(expected, capture.read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
