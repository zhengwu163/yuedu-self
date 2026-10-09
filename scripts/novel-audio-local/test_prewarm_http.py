import importlib
import contextlib
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


class PrewarmTest(unittest.TestCase):
    def setUp(self):
        self.module = importlib.import_module("prewarm_http")

    def test_cli_uses_runtime_status_identity_not_health_shape(self):
        from types import SimpleNamespace
        calls = []
        identity = {"runtimeProfile": "identity", "runtimeProfileInfo": {"identity": "identity"}}
        class Client:
            def json(self, method, route, body=None, lease=None):
                calls.append(route)
                if route.endswith("health"):
                    return {"status": "ok", "apiVersion": "1"}
                if route.endswith("status"):
                    return {"state": "idle", "activeLease": False, **identity}
                if route.endswith("acquire"):
                    return {"leaseId": "owned-lease", **identity}
                return {"state": "idle"}
            def request(self, method, route, body=None, lease=None, audio=False):
                return 200, {"content-type": "audio/ogg", "x-tts-profile": "identity"}, b"OggS" + b"x" * 40
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            diagnostic = root / "diagnostics"
            diagnostic.mkdir()
            text = diagnostic / "text.private.txt"
            text.write_text("甲" * 50, encoding="utf-8")
            config = SimpleNamespace(root=root, active_profile_id="profile")
            with patch.object(self.module, "load_config", return_value=config), \
                    patch.object(self.module, "_read_token", return_value="test-only-token"), \
                    patch.object(self.module, "_Client", return_value=Client()), \
                    patch.object(self.module, "_probe", return_value={"duration": 1}), \
                    contextlib.redirect_stdout(io.StringIO()):
                code = self.module.main(["--config", "unused", "--text-file", str(text), "--voice", "voice", "--cycles", "1", "--cold"])
        self.assertEqual(0, code)
        self.assertEqual("/v1/runtime/status", calls[0])

    def test_preview_and_long_audio_share_explicit_lease(self):
        calls = []
        class Client:
            def json(self, method, route, body=None, lease=None):
                calls.append((route, lease))
                if route.endswith("acquire"):
                    return {"leaseId":"owned-lease", "runtimeProfile":"identity",
                            "runtimeProfileInfo":{"identity":"identity"}}
                return {"state":"idle"}
            def request(self, method, route, body=None, lease=None, audio=False):
                calls.append((route, lease))
                return 200, {"content-type":"audio/ogg","x-tts-profile":"identity"}, b"OggS"+b"x"*40
        with tempfile.TemporaryDirectory() as temp, patch.object(self.module,"_probe",return_value={"durationSeconds":1}):
            result=self.module.prewarm_cycle(Client(), object(), Path(temp), "identity", "voice", ["甲"*50]*3)
        self.assertEqual("/v1/voices/preview",calls[1][0])
        self.assertTrue(all(lease=="owned-lease" for _,lease in calls[1:]))
        self.assertEqual("/v1/runtime/release",calls[-1][0])
        self.assertEqual(3,len(result["synthesizeSeconds"]))

    def test_generation_failure_still_releases_explicit_lease(self):
        released=[]
        class Client:
            def json(self,method,route,body=None,lease=None):
                if route.endswith("acquire"):
                    return {"leaseId":"owned-lease","runtimeProfile":"identity","runtimeProfileInfo":{"identity":"identity"}}
                released.append(lease)
                return {"state":"idle"}
            def request(self,*args,**kwargs):
                return 503,{},b""
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(self.module.SmokeError):
                self.module.prewarm_cycle(Client(),object(),Path(temp),"identity","voice",["甲"*50])
        self.assertEqual(["owned-lease"],released)

    def test_chunks_limit_han_characters_and_utf16(self):
        chunks=self.module.split_text("甲"*151)
        self.assertEqual([50,50,51],[self.module.han_count(chunk) for chunk in chunks])
        for chunk in self.module.split_text("A"*1300+"甲"*110):
            self.assertLessEqual(len(chunk.encode("utf-16-le"))//2,1200)
            self.assertLessEqual(self.module.han_count(chunk),60)
