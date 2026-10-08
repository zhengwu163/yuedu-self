#!/usr/bin/env python3

import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parent / "scripts" / "audit_gson_generic_signature.py"
SPEC = importlib.util.spec_from_file_location("audit_gson_generic_signature", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class GsonGenericSignatureAuditTest(unittest.TestCase):
    def test_dto_with_generic_gson_field_is_candidate(self):
        path = MODULE.ROOT / "app/src/main/java/example/Model.kt"
        source = """
            import com.google.gson.Gson
            data class Model(val items: List<String> = emptyList())
        """

        self.assertTrue(MODULE.is_gson_generic_candidate(path, source))

    def test_consumer_with_generic_gson_usage_is_not_candidate(self):
        path = MODULE.ROOT / "app/src/main/java/example/Coordinator.kt"
        source = """
            import com.google.gson.Gson
            class Coordinator {
                fun encode(items: List<String>) = Gson().toJson(items)
            }
        """

        self.assertFalse(MODULE.is_gson_generic_candidate(path, source))

    def test_json_parser_and_test_sources_are_not_candidates(self):
        production_json = MODULE.ROOT / "app/src/main/java/example/NovelAudioJson.kt"
        test_source = MODULE.ROOT / "app/src/test/java/example/DtoTest.kt"
        source = """
            import com.google.gson.JsonParser
            object NovelAudioJson {
                fun parse(values: List<String>) = JsonParser.parseString(values.toString())
            }
        """

        self.assertFalse(MODULE.is_gson_generic_candidate(production_json, source))
        self.assertFalse(MODULE.is_gson_generic_candidate(test_source, source))


if __name__ == "__main__":
    unittest.main()
