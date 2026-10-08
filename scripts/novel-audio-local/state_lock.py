"""Process-held state ownership; automatically released if the Agent crashes."""

import os
from pathlib import Path


def validate_state(directory):
    directory = Path(directory)
    names = ("agent.lock", "agent.pid", "agent.stop", "agent.status.json",
             "agent.status.json.tmp")
    for path in (directory, *(directory / name for name in names)):
        if path.is_symlink() or path.is_junction():
            raise ValueError("invalid_state")


class StateLock:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.stream = None

    def acquire(self):
        path = self.directory / "agent.lock"
        validate_state(self.directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        stream = path.open("a+b")
        try:
            if not path.stat().st_size:
                stream.write(b"\0")
                stream.flush()
            stream.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            stream.close()
            raise ValueError("agent_already_running") from None
        self.stream = stream

    def close(self):
        if self.stream is None:
            return
        try:
            if os.name == "nt":
                import msvcrt
                self.stream.seek(0)
                msvcrt.locking(self.stream.fileno(), msvcrt.LK_UNLCK, 1)
        finally:
            self.stream.close()
            self.stream = None
