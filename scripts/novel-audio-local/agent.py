"""Lightweight resident Agent: token, HTTP, runtime lease and Worker factory."""

import json
import os
import threading
from pathlib import Path

from scripts.novel_audio_server.api import NovelAudioApi
from scripts.novel_audio_server.http import create_server
from scripts.novel_audio_server.runtime import RuntimeManager

from config import ensure_token, load_config
from model_registry import sha256_file
from state_lock import StateLock
from voices import VoiceCatalog
from worker import InProcessWorkerFactory, SubprocessWorkerFactory


class AgentBackend:
    def __init__(self, profile_id, identity=None, capabilities=None, min_vram_gb=None):
        self.profile = identity or profile_id
        self.profile_id = profile_id
        self.identity = identity or profile_id
        self.capabilities = capabilities or [
            "chapter-analysis",
            "speech-synthesis",
            "voice-design",
        ]
        self.min_vram_gb = min_vram_gb
        self.ready = True

    def close(self):
        pass

    def runtime_metadata(self):
        return {
            "profileId": self.profile_id,
            "identity": self.identity,
            "capabilities": list(self.capabilities),
            "minVramGb": self.min_vram_gb,
            "hardware": {"status": "deferred"},
        }


class LocalAgent:
    def __init__(self, config_path, fake=False):
        self.config_path = Path(config_path)
        self.config = load_config(self.config_path)
        self.state_dir = self.config.root / "state"
        self.stop_marker = self.state_dir / "agent.stop"
        self.fake = fake
        self._pid_owned = False
        self._closed = False
        self._shutdown_failed = False
        self._state_lock = StateLock(self.state_dir)
        self._stop_watch_stop = threading.Event()
        self.catalog = VoiceCatalog(self.config.voice_catalog, self.config.root)
        self.registry = None if fake else self.config.load_model_registry()
        self.runtime_profile = (
            self.registry.active_profile(self.config.active_profile_id)
            if self.registry is not None
            else None
        )
        runtime_identity = "fake-local-v1"
        if self.registry is not None:
            self.catalog.validate_references(self.runtime_profile.capabilities)
            self.registry.verify_profile(
                self.config.active_profile_id,
                catalog=self.catalog,
                available_vram_gb=self.config.minimum_vram_gb,
            )
            catalog_digest = sha256_file(self.catalog.path)
            reference_digest = self.catalog.reference_audio_digest(
                self.runtime_profile.capabilities
            )
            runtime_identity = self.registry.profile_identity(
                self.runtime_profile,
                catalog_digest,
                reference_digest,
            )
        self.token = ensure_token(self.config.token_file)
        if fake:
            self.factory = InProcessWorkerFactory(self.catalog)
        else:
            self.factory = SubprocessWorkerFactory(
                self.config_path,
                expected_identity=runtime_identity,
                startup_timeout=self.config.worker_startup_timeout,
                ipc_timeout=self.config.worker_ipc_timeout,
            )
        self.runtime = RuntimeManager(
            self.factory,
            lease_ttl=self.config.lease_ttl,
            startup_timeout=self.config.worker_startup_timeout,
            profile_id=(
                "fake-local-v1"
                if fake
                else self.runtime_profile.profile_id
            ),
        )
        self.backend = AgentBackend(
            "fake-local-v1"
            if fake
            else self.runtime_profile.profile_id,
            runtime_identity,
            (
                list(self.runtime_profile.capabilities)
                if self.runtime_profile is not None
                else None
            ),
            (
                self.runtime_profile.required_vram_gb
                if self.runtime_profile is not None
                else None
            ),
        )
        self.api = NovelAudioApi(
            token=self.token,
            backend=self.backend,
            catalog=self.catalog,
            runtime=self.runtime,
        )
        self.http = create_server(
            self.config.host,
            self.config.port,
            self.api,
            allow_lan=self.config.allow_lan,
        )

    def close(self):
        if self._closed:
            return
        self._closed = True
        # The shared HTTP server owns API/runtime cleanup.
        try:
            self.http.server_close()
        except Exception:
            self._shutdown_failed = True
            if self._pid_owned:
                self._write_status("failed")
            raise
        else:
            if self._pid_owned:
                self._write_status("stopped")
                self.stop_marker.unlink(missing_ok=True)
                self._clear_pid()
                self._pid_owned = False
        finally:
            self.http.socket.close()
            self._state_lock.close()

    def _status_payload(self, state=None):
        runtime_status = self.runtime.status()
        metadata = self._runtime_metadata()
        value = {
            "state": state or runtime_status.state.value,
            "profileId": metadata["profileId"],
            "identity": metadata["identity"],
            "capabilities": list(metadata["capabilities"]),
            "activeLease": runtime_status.active_lease_count > 0,
            "hardware": {"status": metadata["hardware"]["status"]},
        }
        if self._shutdown_failed:
            value["errorCode"] = "worker_stop_failed"
        return value

    def _write_status(self, state=None):
        try:
            self.state_dir.mkdir(parents=True, exist_ok=True)
            temporary = self.state_dir / "agent.status.json.tmp"
            temporary.write_text(
                json.dumps(
                    self._status_payload(state),
                    ensure_ascii=False,
                    sort_keys=True,
                ),
                encoding="utf-8",
            )
            temporary.replace(self.state_dir / "agent.status.json")
        except OSError:
            pass

    def _write_pid(self):
        self.state_dir.mkdir(parents=True, exist_ok=True)
        (self.state_dir / "agent.pid").write_text(
            f"{os.getpid()}\n", encoding="ascii",
        )

    def _clear_pid(self):
        try:
            # The Windows operator revalidates this PID after graceful exit.
            # It owns removal of the paired PID/owner records under its lock.
            if (self.state_dir / "agent.owner.json").exists():
                return
            path = self.state_dir / "agent.pid"
            if path.read_text(encoding="ascii").strip() == str(os.getpid()):
                path.unlink()
        except OSError:
            pass

    def _watch_stop_marker(self):
        while not self._stop_watch_stop.wait(0.2):
            if self.stop_marker.is_file():
                self.http.shutdown()
                return
            self._write_status()

    def serve(self):
        watcher = None
        try:
            self._state_lock.acquire()
            self.stop_marker.unlink(missing_ok=True)
            self._write_pid()
            self._pid_owned = True
            self._write_status()
            self._stop_watch_stop.clear()
            watcher = threading.Thread(
                target=self._watch_stop_marker,
                name="novel-audio-stop-watch",
                daemon=True,
            )
            watcher.start()
            self.http.serve_forever()
        finally:
            self._stop_watch_stop.set()
            if watcher is not None:
                watcher.join(timeout=1.0)
            self.close()

    def status(self):
        status = self.runtime.status()
        return {
            "state": status.state.value,
            "activeLease": status.active_lease_count > 0,
            "runtimeProfile": self.backend.profile,
            "runtimeProfileInfo": self._runtime_metadata(),
        }

    def _runtime_metadata(self):
        return self.backend.runtime_metadata()
