import tempfile
import unittest
from pathlib import Path


class StateLockTest(unittest.TestCase):
    def test_same_state_directory_cannot_have_two_owners(self):
        from state_lock import StateLock

        with tempfile.TemporaryDirectory() as directory:
            first = StateLock(Path(directory))
            second = StateLock(Path(directory))
            first.acquire()
            try:
                with self.assertRaisesRegex(ValueError, "agent_already_running"):
                    second.acquire()
            finally:
                first.close()
            second.acquire()
            second.close()
            second.close()

    def test_symlinked_state_is_rejected(self):
        from state_lock import StateLock

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "outside"
            target.mkdir()
            link = root / "state"
            link.symlink_to(target, target_is_directory=True)
            with self.assertRaises(ValueError):
                StateLock(link).acquire()

    def test_symlinked_state_files_are_rejected(self):
        from state_lock import StateLock

        for name in ("agent.pid", "agent.stop", "agent.status.json",
                     "agent.status.json.tmp"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                state = root / "state"
                state.mkdir()
                outside = root / "private"
                outside.write_text("preserve")
                (state / name).symlink_to(outside)
                with self.assertRaises(ValueError):
                    StateLock(state).acquire()
                self.assertEqual("preserve", outside.read_text())
