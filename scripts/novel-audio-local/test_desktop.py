"""Run actual desktop command/registry fixtures in native PowerShell 5.1."""
import os
from pathlib import Path
import subprocess
import unittest


class DesktopTest(unittest.TestCase):
    @unittest.skipUnless(os.name == "nt", "native Windows desktop fixture")
    def test_native_launch_preserves_space_paths_and_autostart_ownership(self):
        script = Path(__file__).with_name("test_desktop_windows.ps1")
        result = subprocess.run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy",
             "Bypass", "-File", str(script)],
            capture_output=True, timeout=60,
        )
        self.assertEqual(0, result.returncode, "desktop fixture failed")
        self.assertIn(b"desktop_tests_passed=11", result.stdout)

    def test_desktop_scripts_ship_in_source_bundle(self):
        from package_service import SOURCE_FILES
        for name in ("desktop-common.ps1", "desktop.ps1", "desktop-autostart.ps1"):
            self.assertIn("scripts/novel-audio-local/" + name, SOURCE_FILES)
