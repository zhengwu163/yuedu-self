import json
import importlib.util
import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "ai_tests" / "scripts" / "run_gates.py"
REGISTRY = ROOT / "ai_tests" / "config" / "gate_registry.json"
SPEC = importlib.util.spec_from_file_location("project_gate_runner", RUNNER)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class ProjectGateRunnerTest(unittest.TestCase):
    def test_theme_gate_inputs_can_be_versioned(self):
        # A locally passing gate must also have its config and contract in a fresh checkout.
        paths = [
            "ai_tests/config/theme_token_allowlist.json",
            "docs/project-rules/theme-consistency-iron-rule.md",
        ]
        for path in paths:
            with self.subTest(path=path):
                self.assertTrue((ROOT / path).is_file())
                result = subprocess.run(
                    ["git", "check-ignore", "--no-index", "--", path],
                    cwd=ROOT, capture_output=True, text=True, check=False,
                )
                self.assertEqual(1, result.returncode, f"Gate input is ignored: {path}")

    def test_registry_has_unique_commands_for_all_required_gate_types(self):
        payload = json.loads(REGISTRY.read_text(encoding="utf-8"))
        gates = payload["gates"]
        ids = {gate["id"] for gate in gates}
        self.assertEqual(
            {
                "code-change-has-test",
                "gson-generic-signature",
                "theme-token-violation",
                "host-refresh-coverage",
            },
            ids,
        )
        self.assertEqual(len(gates), len({gate["command"][0] for gate in gates}))

    def test_runner_fails_closed_when_a_registered_gate_is_missing(self):
        with TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            registry = root / "gate_registry.json"
            registry.write_text(
                json.dumps(
                    {
                        "gates": [
                            {
                                "id": "missing",
                                "stages": ["commit"],
                                "command": ["not-present.py"],
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )
            # Exercise the fixture registry itself; an unrelated empty stage is not a failure test.
            with patch.object(runner, "REGISTRY", registry), \
                    patch.object(runner, "SCRIPT_DIR", root), \
                    patch.object(sys, "argv", [str(RUNNER), "--stage", "commit"]):
                self.assertNotEqual(0, runner.main())

    def test_runner_does_not_report_success_when_stage_has_no_checks(self):
        with patch.object(runner, "load_registry", return_value=[
            {"id": "commit-only", "stages": ["commit"], "command": ["check.py"]}
        ]), patch.object(sys, "argv", [str(RUNNER), "--stage", "publish"]):
            self.assertNotEqual(0, runner.main())

    def test_runner_propagates_failure_even_if_another_gate_passes(self):
        gates = [
            {"id": "first", "stages": ["ci"], "command": ["first.py"]},
            {"id": "second", "stages": ["ci"], "command": ["second.py"]},
        ]
        with patch.object(runner, "load_registry", return_value=gates), \
                patch.object(runner, "run_gate", side_effect=[1, 0]) as run, \
                patch.object(sys, "argv", [str(RUNNER), "--stage", "ci"]):
            self.assertNotEqual(0, runner.main())
            self.assertEqual(2, run.call_count)

    def test_runner_reports_success_after_selected_checks_pass(self):
        with patch.object(runner, "load_registry", return_value=[
            {"id": "check", "stages": ["deliver"], "command": ["check.py"]}
        ]), patch.object(runner, "run_gate", return_value=0) as run, \
                patch.object(sys, "argv", [str(RUNNER), "--stage", "deliver"]):
            self.assertEqual(0, runner.main())
            run.assert_called_once()


if __name__ == "__main__":
    unittest.main()
