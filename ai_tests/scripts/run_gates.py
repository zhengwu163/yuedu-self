#!/usr/bin/env python3
"""Run the repository's registered, fail-closed project gates."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "ai_tests" / "config" / "gate_registry.json"
SCRIPT_DIR = ROOT / "ai_tests" / "scripts"
STAGES = {"commit", "ci", "publish", "deliver"}


def load_registry() -> list[dict]:
    if not REGISTRY.is_file():
        raise SystemExit(f"missing gate registry: {REGISTRY}")
    try:
        payload = json.loads(REGISTRY.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"invalid gate registry: {exc}") from exc
    gates = payload.get("gates")
    if not isinstance(gates, list) or not gates:
        raise SystemExit("gate registry must contain a non-empty gates list")
    ids = [gate.get("id") for gate in gates]
    if any(not isinstance(value, str) or not value for value in ids):
        raise SystemExit("every gate must have a non-empty id")
    if len(set(ids)) != len(ids):
        raise SystemExit("gate ids must be unique")
    return gates


def run_gate(gate: dict, stage: str) -> int:
    command = gate.get("command")
    if not isinstance(command, list) or not command or not all(
        isinstance(item, str) and item for item in command
    ):
        print(f"[FAIL] {gate.get('id')}: invalid command", file=sys.stderr)
        return 2
    executable = SCRIPT_DIR / command[0]
    if not executable.is_file():
        print(f"[FAIL] {gate.get('id')}: missing {executable}", file=sys.stderr)
        return 2
    argv = [sys.executable, str(executable), "--stage", stage, *command[1:]]
    print(f"[RUN ] {gate['id']}: {gate.get('description', '')}")
    result = subprocess.run(argv, cwd=ROOT, check=False)
    if result.returncode:
        print(f"[FAIL] {gate['id']} exit={result.returncode}", file=sys.stderr)
    else:
        print(f"[PASS] {gate['id']}")
    return result.returncode


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", required=True, choices=sorted(STAGES))
    args = parser.parse_args()
    selected = [gate for gate in load_registry() if args.stage in gate.get("stages", [])]
    if not selected:
        # An unconfigured stage provides no evidence and must not authorize delivery.
        print(f"[FAIL] no checks registered for stage: {args.stage}", file=sys.stderr)
        return 2
    failures = 0
    for gate in selected:
        failures |= run_gate(gate, args.stage)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
