"""Host capability guards shared by local-service tests."""

import tempfile
import unittest
from pathlib import Path


def _symlinks_supported():
    # Windows only allows symlink creation with Developer Mode or elevation.
    with tempfile.TemporaryDirectory() as directory:
        target = Path(directory) / "target"
        target.write_bytes(b"")
        try:
            (Path(directory) / "link").symlink_to(target)
        except (OSError, NotImplementedError):
            return False
    return True


requires_symlinks = unittest.skipUnless(
    _symlinks_supported(),
    "symlink creation is unavailable on this host",
)
