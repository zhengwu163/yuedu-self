"""网络和云模型均可控替代，安全边界使用真实解析/持久化逻辑。"""
import copy
import contextlib
import io
import json
import sqlite3
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

from bridge import (
    BailianClient, BridgeApi, BridgeConfig, CloudQuotaError, CloudProtocolError,
    LocalQuotaError, UsageGuard, _strict_json_loads, parse_tts_audio_url,
)
from test_bridge import FakeCloud


def analysis():
    return {
        "bookId": "b", "chapterId": "c", "textHash": "h", "analysisVersion": "1",
        "characters": [{"characterId": "char_1", "displayName": "林舟", "stableAliases": []}],
        "units": [{"unitId": "u1", "text": "夜色落下。"}, {"unitId": "u2", "text": "“回家吧。”"}],
        "previousContext": {"recentAssignments": []},
    }


def synthesis(text="原创测试。"):
    return {"text": text, "voiceAssetId": "bailian.narrator", "language": "zh-CN", "speed": 1.0}


class BoundaryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.usage = UsageGuard(self.path / "state.db")
        self.cloud = FakeCloud()
        self.api = BridgeApi(BridgeConfig(dashscope_api_key="secret", bridge_token="local"),
                             self.cloud, self.usage, True)

    def call(self, route, body):
        return self.api.respond("POST", route, "Bearer local", body)

    def test_config_repr_redacts_both_secrets(self):
        result = repr(self.api.config)
        self.assertNotIn("secret", result)
        self.assertNotIn("bridge_token='local'", result)

    def test_config_rejects_duplicate_unknown_and_public_bind(self):
        for value in ("DASHSCOPE_API_KEY=a\nDASHSCOPE_API_KEY=b\n",
                      "BRIDGE_HOST=0.0.0.0\n", "DASHSCOP_API_KEY=secret\n",
                      "DASHSCOPE_API_KEY=secret\\rvalue\n", "BRIDGE_TOKEN=bad token\n"):
            with self.subTest(value=value):
                path = self.path / "local.env"
                path.write_text(value)
                with self.assertRaises(ValueError):
                    BridgeConfig.from_env_file(path)

    def test_all_routes_require_auth_without_cloud_calls(self):
        for route in ("health", "voices", "chapter/analyze", "voices/match",
                      "voices/preview", "tts/synthesize"):
            self.assertEqual(401, self.api.respond("POST", "/v1/" + route, None, {})[0])
        self.assertEqual([], self.cloud.analysis_requests + self.cloud.tts_requests)

    def test_unknown_and_query_routes_rejected(self):
        self.assertEqual(404, self.call("/v1/tts/synthesize?key=abc", synthesis())[0])
        self.assertEqual([], self.cloud.tts_requests)

    def test_invalid_analysis_metadata_never_reaches_cloud(self):
        for change in (
            {"characters": [{"characterId": "c"}]},
            {"characters": [{"characterId": "c", "displayName": "林舟", "stableAliases": [{}]}]},
            {"previousContext": {"recentAssignments": [{"unitId": "x", "speakerId": "foreign"}]}},
            {"analysisVersion": "2"},
        ):
            self.assertEqual(400, self.call("/v1/chapter/analyze", dict(analysis(), **change))[0])
        self.assertEqual([], self.cloud.analysis_requests)

    def test_bad_new_character_and_alias_fields_are_rejected(self):
        for character in ({"temporaryId": "tmp"}, {"temporaryId": [], "displayName": "x"},
                          {"temporaryId": "tmp", "displayName": "x", "gender": "male",
                           "ageRange": "adult", "voicePersona": {"traits": [4]}}):
            self.cloud.analysis_response = {
                "assignments": [{"unitId": "u1", "speakerId": "narrator"},
                                {"unitId": "u2", "speakerId": "char_1"}],
                "newCharacters": [character], "aliasUpdates": [],
            }
            self.assertEqual(502, self.call("/v1/chapter/analyze", analysis())[0])

    def test_success_response_projects_only_contract_fields(self):
        self.cloud.analysis_response["text"] = "cloud-rewritten-body"
        self.cloud.analysis_response["assignments"][0]["text"] = "cloud-rewritten-unit"
        result = self.call("/v1/chapter/analyze", analysis())
        self.assertEqual(200, result[0])
        self.assertNotIn("cloud-rewritten", json.dumps(result))

    def test_contextual_aliases_are_not_promoted(self):
        self.cloud.analysis_response["aliasUpdates"] = [
            {"characterId": "char_1", "stableAliases": ["师父", "哥哥", "他", "阿舟"]}]
        response = self.call("/v1/chapter/analyze", analysis())[2]
        self.assertEqual(["阿舟"], response["aliasUpdates"][0]["stableAliases"])

    def test_malformed_assignment_types_fail_as_protocol_error(self):
        for key in ("speakerId", "unitId"):
            self.cloud.analysis_response = FakeCloud().analysis_response
            self.cloud.analysis_response["assignments"][0][key] = []
            self.assertEqual(502, self.call("/v1/chapter/analyze", analysis())[0])

    def test_invalid_match_lists_return_400(self):
        for key in ("voicePersona", "alreadyUsedVoiceIds"):
            body = {"voicePersona": {"traits": []}, "alreadyUsedVoiceIds": []}
            body[key] = {"traits": [{}]} if key == "voicePersona" else [{}]
            self.assertEqual(400, self.call("/v1/voices/match", body)[0])

    def test_language_and_speed_limits_are_explicit(self):
        for change in ({"language": "en-US"}, {"speed": 0.01}, {"speed": 100},
                       {"speed": True}, {"text": "\ud800"}):
            self.assertEqual(400, self.call("/v1/tts/synthesize", dict(synthesis(), **change))[0])
        self.assertEqual([], self.cloud.tts_requests)

    def test_1200_utf16_segment_supported_and_1201_rejected(self):
        self.assertEqual(200, self.call("/v1/tts/synthesize", synthesis("文" * 1200))[0])
        self.assertEqual(400, self.call("/v1/tts/synthesize", synthesis("文" * 1201))[0])

    def test_free_quota_latches_when_persistence_fails(self):
        self.cloud.synthesize = lambda _: (_ for _ in ()).throw(CloudQuotaError())
        with patch.object(self.usage, "block_cloud_quota", side_effect=LocalQuotaError()):
            self.assertEqual(429, self.call("/v1/tts/synthesize", synthesis())[0])
        self.cloud = FakeCloud()
        self.api.cloud = self.cloud
        self.assertEqual(429, self.call("/v1/tts/synthesize", synthesis())[0])
        self.assertEqual([], self.cloud.tts_requests)

    def test_free_quota_block_survives_new_usage_object(self):
        self.usage.block_cloud_quota()
        with self.assertRaises(LocalQuotaError):
            UsageGuard(self.path / "state.db").reserve("tts", 1)

    def test_missing_budget_row_or_schema_does_not_reset_usage(self):
        for sql in ("DELETE FROM budget", "DROP TABLE budget"):
            path = self.path / ("missing-row.db" if sql.startswith("DELETE") else "missing-schema.db")
            UsageGuard(path).reserve("tts", 1)
            with contextlib.closing(sqlite3.connect(path)) as db, db:
                db.execute(sql)
            with self.assertRaises(LocalQuotaError):
                UsageGuard(path).reserve("tts", 1)

    def test_truncated_budget_file_is_not_a_fresh_budget(self):
        path = self.path / "truncated.db"
        path.write_bytes(b"")
        with self.assertRaises(LocalQuotaError):
            UsageGuard(path).reserve("tts", 1)

    def test_health_respects_persisted_quota_block(self):
        self.usage.block_cloud_quota()
        api = BridgeApi(self.api.config, self.cloud, UsageGuard(self.path / "state.db"), True)
        result = api.respond("GET", "/v1/health", "Bearer local", None)
        self.assertFalse(result[2]["directorReady"])
        self.assertFalse(result[2]["ttsReady"])

    def test_quota_reservation_atomic_across_instances(self):
        limits = {"analysis_requests": 3, "analysis_characters": 50,
                  "tts_requests": 3, "tts_characters": 50}
        def attempt(_):
            try:
                UsageGuard(self.path / "count.db", limits).reserve("analysis", 1)
                return 1
            except LocalQuotaError:
                return 0
        with ThreadPoolExecutor(max_workers=8) as pool:
            self.assertEqual(3, sum(pool.map(attempt, range(30))))

    def test_nonfinite_duplicate_surrogate_json_rejected(self):
        for raw in ('{"a":{"x":1,"x":2}}', '{"x":NaN}', '{"x":Infinity}', '{"x":"\\ud800"}'):
            with self.assertRaises(ValueError):
                _strict_json_loads(raw)

    def test_official_http_audio_url_upgraded_only_for_allowlisted_host(self):
        url = "http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a.wav?sign=example"
        parsed = parse_tts_audio_url({"output": {"audio": {"url": url}}})
        self.assertEqual(url.replace("http:", "https:", 1), parsed)

    def test_cloud_free_quota_code_takes_precedence_over_http_403(self):
        error = BailianClient._cloud_error(403, b'{"code":"AllocationQuota.FreeTierOnly"}')
        self.assertIsInstance(error, CloudQuotaError)

    def test_generic_429_not_labeled_free_quota(self):
        error = BailianClient._cloud_error(429, b'{"code":"Throttling.RateQuota"}')
        self.assertNotIsInstance(error, CloudQuotaError)


if __name__ == "__main__":
    unittest.main()
