import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent


class PowerShellContractTest(unittest.TestCase):
    """Source contracts only; Windows behavior is tested by test_operator_windows.ps1."""

    def source(self, name):
        return (ROOT / name).read_text(encoding="utf-8")

    def common(self):
        path = ROOT / "operator-common.ps1"
        self.assertTrue(path.is_file(), "shared Windows safety implementation missing")
        return path.read_text(encoding="utf-8")

    def test_operator_scripts_preserve_offline_and_secret_boundaries(self):
        scripts = {
            "install.ps1",
            "start-agent.ps1",
            "stop-agent.ps1",
            "check-models.ps1",
        }
        for name in scripts:
            with self.subTest(name=name):
                path = ROOT / name
                self.assertTrue(path.is_file())
                content = path.read_text(encoding="utf-8")
                self.assertIn("ConfigPath", content)
                self.assertNotIn("Invoke-WebRequest", content)
                self.assertNotIn("bitsadmin", content.lower())
                self.assertNotIn("git clone", content.lower())

    def test_start_checks_before_launching_and_propagates_exit_code(self):
        content = (ROOT / "start-agent.ps1").read_text(encoding="utf-8")
        self.assertIn("Invoke-OperatorCheck", content)
        self.assertIn("--serve", content)
        self.assertIn("exit $checkCode", content)
        self.assertIn("-PassThru", content)
        self.assertIn("agent.pid", content)
        self.assertIn("HasExited", content)
        self.assertIn("--require-windows-runtime", self.common())

    def test_stop_verifies_process_ownership_before_force(self):
        content = (ROOT / "stop-agent.ps1").read_text(encoding="utf-8")
        self.assertIn("agent.stop", content)
        self.assertIn("agent.pid", content)
        self.assertIn("Get-AgentRecord", content)
        self.assertIn("Stop-OwnedRecords", content)
        common = self.common()
        self.assertIn("Win32_Process", common)
        self.assertIn("CommandLine", common)
        self.assertIn("CreationDate", common)
        self.assertIn("taskkill.exe", common)
        # /T discovers unrecorded descendants after the identity check.
        self.assertNotIn("/T", common)

    def test_dead_agent_recovery_preserves_ownership_and_lock_guards(self):
        for name in ("start-agent.ps1", "stop-agent.ps1"):
            with self.subTest(name=name):
                self.assertIn("Recover-ExitedAgentState -Context $context -Record $record",
                              self.source(name))
        common = self.common()
        recovery = common[common.index("function Recover-ExitedAgentState {"):]
        recovery = recovery.split("\nfunction ", 1)[0]
        self.assertIn("Enter-StateLock -Context $Context -Offset 0", recovery)
        self.assertIn("Assert-StateOwner", recovery)
        self.assertIn("Get-AgentRecord", recovery)
        self.assertIn("$current.Process", recovery)
        self.assertNotIn("Invoke-AgentTermination", recovery)
        self.assertNotIn("Stop-OwnedRecords", recovery)

    def test_launch_waits_for_its_own_pid_with_quoted_absolute_arguments(self):
        content = (ROOT / "start-agent.ps1").read_text(encoding="utf-8")
        self.assertIn("StartupTimeoutSeconds", content)
        self.assertIn("Get-OperatorContext", content)
        self.assertIn("$process.Id", content)
        self.assertIn("ConvertTo-Json", content)
        self.assertIn("Wait-ForHealthyAgent", content)

    def test_start_releases_python_state_lock_before_spawn_and_pins_logs(self):
        content = self.source("start-agent.ps1")
        reserve = content.index("Enter-StateLock -Context $context -Offset 0")
        release = content.index("Exit-OperatorLock $reservationLock", reserve)
        spawn = content.index("Start-Process")
        self.assertLess(release, spawn)
        self.assertIn("[NovelAudio.OperatorNative]::PinDirectory($logs, $false)", content)
        self.assertIn("$logGuard.Dispose()", content)

    def test_start_marks_state_cleanup_before_owner_write(self):
        content = self.source("start-agent.ps1")
        pin = content.index("$pin = New-ProcessPin -Process $processInfo")
        attempted = content.index("$stateWriteAttempted = $true", pin)
        owner = content.index("Save-AgentOwner -Context $context -Pin $pin", attempted)
        cleanup = content.index("$null -ne $pin -and $stateWriteAttempted")
        self.assertLess(pin, attempted)
        self.assertLess(attempted, owner)
        self.assertIn("$stateWriteAttempted = $false", content)
        self.assertIn("$stateWriteAttempted) {", content[cleanup:])

    def test_process_identity_callers_use_agent_pid_not_reserved_pid(self):
        for name in ("start-agent.ps1", "stop-agent.ps1",
                     "operator-common.ps1", "test_operator_windows.ps1"):
            text = self.source(name)
            with self.subTest(name=name):
                self.assertNotIn("Assert-StateOwner -Context $context -Pid", text)
                self.assertNotIn("Invoke-AgentTermination -Context $context -Pid", text)
                self.assertNotRegex(text, r"(?im)^\s*param\([^\r\n]*\$Pid\b")
        common = self.common()
        self.assertIn("[int]$AgentPid", common)
        windows_test = self.source("test_operator_windows.ps1")
        self.assertIn("param([int]$AgentPid)", windows_test)

    def test_windows_lock_contract_uses_fixed_operator_byte(self):
        text = self.source("test_operator_windows.ps1")
        self.assertIn("Enter-OperatorLock -Context $context", text)
        self.assertIn("Enter-OperatorLock -Context $context -TimeoutSeconds 0", text)
        self.assertNotIn("Enter-OperatorLock -Context $context -Offset", text)

    def test_stop_rechecks_pid_creation_and_tracks_children(self):
        content = (ROOT / "stop-agent.ps1").read_text(encoding="utf-8")
        common = self.common()
        self.assertIn("ParentProcessId", common)
        self.assertIn("CreationDate -eq", common)
        self.assertIn("New-ProcessPin", common)
        self.assertIn("Get-AgentRecord", content)

    def test_install_creates_runtime_directories_without_model_download(self):
        content = (ROOT / "install.ps1").read_text(encoding="utf-8")
        for directory in (
            "config",
            "state",
            "logs",
            "diagnostics",
            "manifests",
            "runtime",
        ):
            self.assertIn(directory, content)
        self.assertNotIn("Download", content)
        self.assertNotIn("download", content)

    def test_stop_no_longer_uses_regex_substring_ownership(self):
        content = self.source("stop-agent.ps1")
        self.assertNotIn("$commandLine -notmatch", content)
        common = self.common()
        self.assertIn("CommandLineToArgvW", common)
        self.assertIn("LocalFree", common)
        self.assertIn("Test-AgentCommand", common)
        self.assertIn("OrdinalIgnoreCase", common)
        self.assertIn('"--serve"', common)
        self.assertIn('"--config"', common)

    def test_all_direct_entries_validate_state_before_work(self):
        for name in ("install.ps1", "start-agent.ps1", "stop-agent.ps1", "check-models.ps1"):
            with self.subTest(name=name):
                text = self.source(name)
                self.assertIn('"operator-common.ps1"', text)
                self.assertIn("Assert-OperatorState", text)
        common = self.common()
        for name in ("agent.lock", "agent.pid", "agent.stop", "agent.status.json",
                     "agent.status.json.tmp", "agent.owner.json"):
            self.assertIn(name, common)
        self.assertIn("ReparsePoint", common)
        self.assertIn("OPEN_REPARSE_POINT", common)
        self.assertIn("Assert-NoReparsePath", common)

    def test_stop_state_cleanup_requires_python_compatible_byte_lock(self):
        common = self.common()
        self.assertIn(".Lock($Offset, 1)", common)
        self.assertIn(".Unlock($Lock.Offset, 1)", common)
        stop = self.source("stop-agent.ps1")
        self.assertIn("Enter-OperatorLock", stop)
        cleanup = stop.index("Enter-StateLock -Context $context -Offset 0")
        self.assertLess(cleanup, stop.index("Write-SafeStateText"))
        self.assertIn("Assert-StateOwner", stop[cleanup:])
        self.assertIn("finally", stop[cleanup:])

    def test_start_reuses_authenticated_agent_before_checks_and_spawn(self):
        text = self.source("start-agent.ps1")
        self.assertLess(text.index("Enter-OperatorLock"), text.index("Get-AgentRecord"))
        self.assertLess(text.index("Test-AgentHealth"), text.index("Invoke-OperatorCheck"))
        self.assertLess(text.index("Test-AgentHealth"), text.index("Start-Process"))
        self.assertIn("Save-AgentOwner", text)
        self.assertNotIn("agent.status.json", text)

    def test_health_is_token_authenticated_loopback_without_proxy_or_redirects(self):
        common = self.common()
        self.assertIn("tokenFile", common)
        self.assertIn("/v1/health", common)
        self.assertIn("127.0.0.1", common)
        self.assertIn("AllowAutoRedirect = $false", common)
        self.assertIn("Proxy = $null", common)
        self.assertIn('Headers["Authorization"]', common)
        self.assertIn("Get-NetTCPConnection", common)
        self.assertNotRegex(common, r"(?i)(Write-Output|Write-Host).*token")

    def test_owner_creation_is_persisted_and_revalidated(self):
        common = self.common()
        self.assertIn("Save-AgentOwner", common)
        self.assertIn("creationTicks", common)
        self.assertIn("configHash", common)
        self.assertIn("GetProcessTimes", common)

    def test_install_seeds_both_templates_without_overwriting(self):
        text = self.source("install.ps1")
        self.assertIn("Initialize-OperatorTemplates", text)
        common = self.common()
        self.assertIn("config/models.windows.example.json", common)
        self.assertIn("voices/standard.json", common)
        self.assertIn("CreateNew", common)
        self.assertNotIn("Copy-Item", text)

    def test_windows_lifecycle_suite_is_explicit_and_not_pester_dependent(self):
        path = ROOT / "test_operator_windows.ps1"
        self.assertTrue(path.is_file())
        text = path.read_text(encoding="utf-8")
        for scenario in ("server.py.bak", "CreationDate", "Junction", "SymbolicLink",
                         "concurrent", "unhealthy", "redirect", "new owner",
                         "Invoke-AgentTermination"):
            self.assertIn(scenario, text)
        self.assertNotIn("Import-Module Pester", text)

    def test_windows_lifecycle_fixture_matches_exact_command_contract(self):
        text = self.source("test_operator_windows.ps1")
        # Get-ConfiguredPython rejects a missing interpreter, so the fixture
        # must create its own and use it for config, image path, and argv[0].
        self.assertNotIn("C:\\Python\\python.exe", text)
        self.assertIn('$fakePython = Join-Path $script:fixture "python.exe"', text)
        self.assertIn("Set-Content -LiteralPath $fakePython", text)
        self.assertIn("pythonExecutable = $fakePython", text)
        self.assertGreaterEqual(text.count("ExecutablePath = $fakePython"), 2)
        self.assertEqual(text.count("$fakePython, $context.ScriptPath, $context.ConfigPath"), 2)


if __name__ == "__main__":
    unittest.main()
