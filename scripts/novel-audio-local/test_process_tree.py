import signal
import subprocess
import unittest
from unittest.mock import patch

from process_tree import process_is_gone, terminate_process_tree


class FakeProcess:
    def __init__(self, pid=4242, running=True):
        self.pid = pid
        self.running = running
        self.wait_count = 0
        self.terminate_count = 0

    def poll(self):
        return None if self.running else 0

    def terminate(self):
        self.terminate_count += 1

    def wait(self, timeout=None):
        self.wait_count += 1
        if self.running:
            raise subprocess.TimeoutExpired("fake", timeout)
        return 0


class ProcessTreeTest(unittest.TestCase):
    def test_windows_cleanup_uses_owned_pid_tree_kill_after_graceful_attempt(self):
        process = FakeProcess()
        commands = []

        result = terminate_process_tree(
            process,
            timeout=0.01,
            command_runner=lambda argv: commands.append(argv),
            platform_name="nt",
        )

        self.assertFalse(result)
        self.assertEqual(1, process.terminate_count)
        self.assertEqual([["taskkill", "/PID", "4242", "/T", "/F"]], commands)
        self.assertGreaterEqual(process.wait_count, 2)

    def test_posix_cleanup_targets_process_group_then_escalates(self):
        process = FakeProcess()
        sent = []

        with patch("process_tree.os.getpgid", return_value=4242), patch(
            "process_tree.os.killpg",
            side_effect=lambda pgid, value: sent.append((pgid, value)),
        ):
            self.assertFalse(
                terminate_process_tree(
                    process,
                    timeout=0.01,
                    command_runner=lambda argv: None,
                    platform_name="posix",
                )
            )

        self.assertEqual([(4242, signal.SIGTERM), (4242, signal.SIGKILL)], sent)

    def test_cleanup_skips_already_exited_process_and_probe_is_safe(self):
        process = FakeProcess(running=False)
        commands = []

        self.assertTrue(
            terminate_process_tree(
                process,
                timeout=0.01,
                command_runner=lambda argv: commands.append(argv),
                platform_name="nt",
            )
        )
        self.assertEqual([], commands)
        self.assertTrue(process_is_gone(4242, lambda pid: False))

        def missing_process(_pid):
            raise OSError()

        self.assertTrue(process_is_gone(4242, missing_process))


if __name__ == "__main__":
    unittest.main()
