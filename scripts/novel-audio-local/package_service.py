"""Build the allowlisted source bundle used for Windows service handoff."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


SOURCE_FILES = (
    "scripts/novel-audio-local/README.md",
    "scripts/novel-audio-local/SMOKE_CHECKLIST.md",
    "scripts/novel-audio-local/WINDOWS_HANDOFF.md",
    "scripts/novel-audio-local/__init__.py",
    "scripts/novel-audio-local/agent.py",
    "scripts/novel-audio-local/audio.py",
    "scripts/novel-audio-local/backend.py",
    "scripts/novel-audio-local/check-models.ps1",
    "scripts/novel-audio-local/checks.py",
    "scripts/novel-audio-local/config.py",
    "scripts/novel-audio-local/install.ps1",
    "scripts/novel-audio-local/local-model.example.json",
    "scripts/novel-audio-local/model_registry.py",
    "scripts/novel-audio-local/operator-common.ps1",
    "scripts/novel-audio-local/process_tree.py",
    "scripts/novel-audio-local/qwen_backend.py",
    "scripts/novel-audio-local/runtime_probe.py",
    "scripts/novel-audio-local/server.py",
    "scripts/novel-audio-local/smoke_http.py",
    "scripts/novel-audio-local/start-agent.ps1",
    "scripts/novel-audio-local/state_lock.py",
    "scripts/novel-audio-local/stop-agent.ps1",
    "scripts/novel-audio-local/test_operator_windows.ps1",
    "scripts/novel-audio-local/voices.py",
    "scripts/novel-audio-local/worker.py",
    "scripts/novel-audio-local/config/models.windows.example.json",
    "scripts/novel-audio-local/voices/standard.json",
    "scripts/novel_audio_server/__init__.py",
    "scripts/novel_audio_server/api.py",
    "scripts/novel_audio_server/errors.py",
    "scripts/novel_audio_server/http.py",
    "scripts/novel_audio_server/protocol.py",
    "scripts/novel_audio_server/runtime.py",
)

SOURCE_BRANCH = "feat/local-model-service"


def source_revision(root):
    root = Path(root)
    try:
        branch = subprocess.run(
            ["git", "-C", str(root), "branch", "--show-current"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=5,
            check=False,
        )
        head = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "HEAD"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=5,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise ValueError("invalid_source_revision") from None
    branch_name = branch.stdout.strip()
    source_head = head.stdout.strip()
    if (
        branch.returncode != 0
        or head.returncode != 0
        or branch_name != SOURCE_BRANCH
        or not re.fullmatch(r"[0-9a-f]{40}", source_head)
    ):
        raise ValueError("invalid_source_revision")
    return {"sourceBranch": branch_name, "sourceHead": source_head}


def _is_link(path):
    return path.is_symlink() or bool(
        getattr(path, "is_junction", lambda: False)()
    )


def _read_sources(root):
    root = Path(root)
    if _is_link(root):
        raise ValueError("invalid_source")
    contents = {}
    for name in SOURCE_FILES:
        path = root / name
        if not path.is_file():
            raise ValueError("invalid_source")
        current = path
        parents = []
        while current != root:
            parents.append(current)
            current = current.parent
        if any(_is_link(parent) for parent in parents):
            raise ValueError("invalid_source")
        try:
            contents[name] = path.read_bytes()
        except OSError:
            raise ValueError("invalid_source") from None
    return contents


def _manifest(contents, revision):
    return {
        **revision,
        "snapshotKind": "working-tree",
        "sha256": {
            name: hashlib.sha256(data).hexdigest()
            for name, data in contents.items()
        },
    }


def _validate_target_parent(target):
    current = target.parent
    while True:
        if _is_link(current):
            raise ValueError("invalid_target")
        parent = current.parent
        if parent == current:
            return
        current = parent


def build_bundle(root, target):
    """Write an exclusive ZIP containing only the service source allowlist."""
    revision = source_revision(root)
    contents = _read_sources(root)
    target = Path(target)
    _validate_target_parent(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    created = False
    try:
        with target.open("xb") as stream:
            created = True
            with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                for name, data in contents.items():
                    archive.writestr(name, data)
                manifest = json.dumps(
                    _manifest(contents, revision),
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                ).encode("utf-8")
                archive.writestr("SERVICE_MANIFEST.json", manifest)
            stream.flush()
            os.fsync(stream.fileno())
    except FileExistsError:
        raise
    except (OSError, ValueError, zipfile.BadZipFile):
        if created:
            try:
                target.unlink()
            except OSError:
                pass
        raise ValueError("bundle_failed") from None
    return target


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        build_bundle(args.root, args.output)
    except (OSError, ValueError, zipfile.BadZipFile):
        print(json.dumps({"state": "failed", "errorCode": "bundle_failed"},
                         sort_keys=True))
        return 2
    print(json.dumps({"state": "created"}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
