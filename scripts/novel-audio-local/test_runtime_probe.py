"""Runtime contracts use doubles; host subprocess tests use only the test Python."""
import contextlib
import importlib
import importlib.util
import io
import json
import os
import subprocess
import sys
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch


PYTHON_NAMES = ("pythonRuntime", "qwenTts", "torch", "cuda")
RUNTIME_NAMES = PYTHON_NAMES + ("llamaServer", "audioCodec")


class GoodRunner:
    def __init__(self, replacements=None):
        self.calls = []
        self.replacements = replacements or {}

    def __call__(self, argv, *, timeout, max_output_bytes):
        from runtime_probe import ProbeOutput
        self.calls.append((argv, timeout, max_output_bytes))
        key = "--python-probe" if "--python-probe" in argv else (Path(argv[0]).stem, argv[-1])
        if key in self.replacements:
            result = self.replacements[key]
            if isinstance(result, Exception):
                raise result
            return result
        if key == "--python-probe":
            body = json.dumps({name: ["PASS", "ok"] for name in PYTHON_NAMES})
        else:
            body = {
                ("runner", "--version"): "version: 11320 (abcdef)\nbuilt with MSVC\n",
                ("runner", "--help"): "  -ngl, --gpu-layers N\n  -dev, --device <dev1,dev2>\n",
                ("runner", "--list-devices"): "Available devices:\n  CUDA0: NVIDIA GeForce RTX 5090 D V2 (24576 MiB)\n",
                ("ffmpeg", "-version"): "ffmpeg version n9.0.2 Copyright\n",
                ("ffprobe", "-version"): "ffprobe version n9.0.2 Copyright\n",
                ("ffmpeg", "-muxers"): "Muxers:\n  E ogg             Ogg\n",
                ("ffmpeg", "-encoders"): " A..... libopus          libopus Opus\n",
                ("ffmpeg", "-decoders"): " A....D opus             Opus\n",
                ("ffprobe", "-decoders"): " A....D opus             Opus\n",
            }[key]
        return ProbeOutput("ok", body.encode())


def fake_config():
    return SimpleNamespace(
        text=SimpleNamespace(runner=Path("/test/runner.exe")),
        tts=SimpleNamespace(python_executable=Path("/test/pythonExecutable.exe"),
                            ffmpeg=Path("/test/ffmpeg.exe"), ffprobe=Path("/test/ffprobe.exe")),
        minimum_vram_gb=24,
    )


class ProbeTestCase(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(importlib.util.find_spec("runtime_probe"),
                             "runtime_probe must implement bounded read-only probes")
        self.probe = importlib.import_module("runtime_probe")


class RuntimeProbeTest(ProbeTestCase):
    def test_missing_help_flags_at_output_cap_are_parsed_in_bounded_time(self):
        # Alarm bounds the regression test itself: an unanchored DOTALL lookahead
        # used to retry the entire remaining buffer at every character.
        if os.name == "nt":
            self.skipTest("POSIX alarm guards parser regression on the Mac host")
        import signal
        def expired(*_args):
            raise AssertionError("help parser exceeded one second")
        previous = signal.signal(signal.SIGALRM, expired)
        try:
            signal.setitimer(signal.ITIMER_REAL, 1)
            runner = GoodRunner({("runner", "--help"): self.probe.ProbeOutput(
                "ok", b"x" * self.probe.MAX_OUTPUT_BYTES)})
            result = self.probe.probe_runtime(fake_config(), platform_name="nt", runner=runner)
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
            signal.signal(signal.SIGALRM, previous)
        self.assertEqual(("FAIL", "llama_cuda_flags_missing"), result["llamaServer"])

    def test_python_payload_preserves_meaningful_blocked_status(self):
        value = {name: ["PASS", "ok"] for name in PYTHON_NAMES}
        value["cuda"] = ["BLOCKED", "insufficient_vram"]
        runner = GoodRunner({"--python-probe": self.probe.ProbeOutput("ok", json.dumps(value).encode())})
        result = self.probe.probe_runtime(fake_config(), platform_name="nt", runner=runner)
        self.assertEqual(("BLOCKED", "insufficient_vram"), result["cuda"])

    def test_only_allowlisted_read_only_commands_are_used(self):
        runner = GoodRunner()
        results = self.probe.probe_runtime(fake_config(), platform_name="nt", runner=runner)
        self.assertEqual({name: ("PASS", "ok") for name in RUNTIME_NAMES}, results)
        # One Python probe, three llama probes, six ffmpeg/ffprobe list probes.
        self.assertEqual(10, len(runner.calls))
        python_argv = runner.calls[0][0]
        self.assertEqual(str(fake_config().tts.python_executable), python_argv[0])
        self.assertEqual(["-I", "-B"], python_argv[1:3])
        self.assertEqual("--python-probe", python_argv[-2])
        self.assertTrue(python_argv[3].endswith("runtime_probe.py"))
        allowed = {"--version", "--help", "--list-devices", "-version",
                   "-muxers", "-encoders", "-decoders"}
        for argv, timeout, cap in runner.calls:
            self.assertGreater(timeout, 0)
            self.assertLessEqual(timeout, 60)
            self.assertGreater(cap, 0)
            self.assertLessEqual(cap, 1024 * 1024)
            if argv is not python_argv:
                self.assertIn(argv[-1], allowed)
                self.assertNotIn("-i", argv)
                self.assertNotIn("-m", argv)

    def test_non_windows_is_not_checked_and_never_runs_runner(self):
        runner = Mock(side_effect=AssertionError("no execution"))
        self.assertEqual({name: ("NOT_CHECKED", "windows_only") for name in RUNTIME_NAMES},
                         self.probe.probe_runtime(fake_config(), platform_name="posix", runner=runner))
        runner.assert_not_called()

    def test_platform_override_alone_cannot_execute_windows_programs_on_mac(self):
        if os.name == "nt":
            self.skipTest("host guard is for non-Windows")
        with patch("subprocess.Popen", side_effect=AssertionError("host guard")):
            results = self.probe.probe_runtime(fake_config(), platform_name="nt")
        self.assertTrue(all(item == ("NOT_CHECKED", "windows_only") for item in results.values()))

    def test_process_failures_are_fixed_codes_without_exception_or_output_echo(self):
        for error, expected in (
            (TimeoutError("/private/token"), "probe_timeout"),
            (OSError("/private/token"), "probe_unavailable"),
            (RuntimeError("/private/token"), "probe_failed"),
        ):
            with self.subTest(expected=expected):
                runner = GoodRunner({"--python-probe": error})
                captured = io.StringIO()
                with contextlib.redirect_stdout(captured), contextlib.redirect_stderr(captured):
                    result = self.probe.probe_runtime(fake_config(), platform_name="nt", runner=runner)
                self.assertEqual(("FAIL", expected), result["pythonRuntime"])
                self.assertNotIn("/private", json.dumps(result))
                self.assertEqual("", captured.getvalue())

    def test_python_payload_rejects_malformed_missing_and_unapproved_codes(self):
        for payload in (b"noise /private", b"{}", b"[]",
                        json.dumps({n: ["PASS", "/private"] for n in PYTHON_NAMES}).encode(),
                        json.dumps({n: ["PASS", "python_runtime_incompatible"] for n in PYTHON_NAMES}).encode()):
            with self.subTest(payload=payload):
                runner = GoodRunner({"--python-probe": self.probe.ProbeOutput("ok", payload)})
                result = self.probe.probe_runtime(fake_config(), platform_name="nt", runner=runner)
                self.assertEqual(("FAIL", "invalid_probe_output"), result["pythonRuntime"])

    def test_runner_output_is_bounded_even_for_injected_doubles(self):
        runner = GoodRunner({"--python-probe": self.probe.ProbeOutput("ok", b"x" * (1024 * 1024 + 1))})
        result = self.probe.probe_runtime(fake_config(), platform_name="nt", runner=runner)
        self.assertEqual(("FAIL", "probe_output_limit"), result["pythonRuntime"])

    def test_llama_requires_version_cuda_arguments_and_exact_cuda0_device(self):
        for flag, body, code in (
            ("--version", b"random /private", "llama_version_unavailable"),
            ("--help", b"-ngl -device-other", "llama_cuda_flags_missing"),
            ("--help", b"-dev -ngl-other", "llama_cuda_flags_missing"),
            ("--list-devices", b"CUDA1: GPU\nVulkan0: GPU", "llama_cuda0_unavailable"),
            ("--list-devices", b"log mentions CUDA0 but no device entry", "llama_cuda0_unavailable"),
        ):
            with self.subTest(flag=flag, body=body):
                runner = GoodRunner({("runner", flag): self.probe.ProbeOutput("ok", body)})
                result = self.probe.probe_runtime(fake_config(), platform_name="nt", runner=runner)
                self.assertEqual(("FAIL", code), result["llamaServer"])

    def test_audio_requires_actual_list_entries_not_feature_substrings(self):
        for executable, flag, body, code in (
            ("ffmpeg", "-version", b"bad", "ffmpeg_version_unavailable"),
            ("ffprobe", "-version", b"bad", "ffprobe_version_unavailable"),
            ("ffmpeg", "-muxers", b" D ogg Ogg\n", "ogg_muxer_missing"),
            ("ffmpeg", "-encoders", b" --enable-libopus\n A..... opus Opus", "libopus_encoder_missing"),
            ("ffmpeg", "-decoders", b" A..... notopus Not Opus", "opus_decoder_missing"),
            ("ffprobe", "-decoders", b" --enable-libopus", "opus_decoder_missing"),
        ):
            with self.subTest(executable=executable, flag=flag):
                runner = GoodRunner({(executable, flag): self.probe.ProbeOutput("ok", body)})
                result = self.probe.probe_runtime(fake_config(), platform_name="nt", runner=runner)
                self.assertEqual(("FAIL", code), result["audioCodec"])


class PythonChildTest(ProbeTestCase):
    def test_child_entry_hides_native_logs_and_outputs_only_fixed_json(self):
        # Execute the entry in an isolated host Python, with all GPU work doubled.
        source = (
            "import importlib.util,os,sys;"
            f"spec=importlib.util.spec_from_file_location('runtime_probe',{self.probe.__file__!r});"
            "m=importlib.util.module_from_spec(spec);sys.modules[spec.name]=m;"
            "spec.loader.exec_module(m);"
            "m._python_checks=lambda minimum:(os.write(1,b'/private/native'),"
            "os.write(2,b'/private/exception'),"
            "{name:('PASS','ok') for name in m.PYTHON_NAMES})[-1];"
            "sys.argv=['runtime_probe','--python-probe','24'];"
            "sys.exit(m._child_main())"
        )
        result = subprocess.run([sys.executable, "-I", "-B", "-c", source],
                                capture_output=True, timeout=3)
        self.assertEqual(0, result.returncode)
        self.assertEqual(b"", result.stderr)
        self.assertEqual({name: ["PASS", "ok"] for name in PYTHON_NAMES},
                         json.loads(result.stdout))

    def child_checks(self, *, version=(3, 12, 15), bits=8, platform="win32",
                     implementation="cpython", torch_version="2.9.2+cu130",
                     audio_version="2.9.2+cu130", cuda_version="13.0",
                     available=True, name="NVIDIA GeForce RTX 5090 D V2",
                     memory=24 * 1024 ** 3, minimum=24, import_error=None):
        gpu = SimpleNamespace(name=name, total_memory=memory)
        cuda = SimpleNamespace(is_available=Mock(return_value=available),
                               get_device_properties=Mock(return_value=gpu))
        modules = {
            "qwen_tts": SimpleNamespace(),
            "torch": SimpleNamespace(__version__=torch_version,
                                     version=SimpleNamespace(cuda=cuda_version), cuda=cuda),
            "torchaudio": SimpleNamespace(__version__=audio_version),
        }
        def load(name):
            if name == import_error:
                raise ImportError("/private/library.dll")
            return modules[name]
        with patch.object(self.probe.sys, "version_info", version), \
             patch.object(self.probe.sys, "platform", platform), \
             patch.object(self.probe.sys, "implementation", SimpleNamespace(name=implementation)), \
             patch.object(self.probe.struct, "calcsize", return_value=bits), \
             patch.object(self.probe.importlib, "import_module", side_effect=load) as importer:
            result = self.probe._python_checks(minimum)
        return result, importer, cuda

    def test_python_patch_and_compatible_torch_patch_are_not_pinned(self):
        result, importer, cuda = self.child_checks()
        self.assertEqual({name: ("PASS", "ok") for name in PYTHON_NAMES}, result)
        self.assertEqual({"torch", "torchaudio", "qwen_tts"},
                         {call.args[0] for call in importer.call_args_list})
        cuda.get_device_properties.assert_called_once_with(0)

    def test_cpython_windows_64bit_312_is_required_before_library_import(self):
        for values in (dict(version=(3, 13, 1)), dict(bits=4), dict(platform="linux"),
                       dict(implementation="pypy")):
            with self.subTest(values=values):
                result, importer, _ = self.child_checks(**values)
                self.assertEqual(("FAIL", "python_runtime_incompatible"), result["pythonRuntime"])
                importer.assert_not_called()

    def test_import_errors_are_sanitized_and_do_not_claim_dependent_pass(self):
        for name, check, code in (("qwen_tts", "qwenTts", "qwen_import_failed"),
                                  ("torch", "torch", "torch_import_failed"),
                                  ("torchaudio", "torch", "torchaudio_import_failed")):
            with self.subTest(name=name):
                result, _, _ = self.child_checks(import_error=name)
                self.assertEqual(("FAIL", code), result[check])
                self.assertNotIn("library.dll", json.dumps(result))
                if name != "qwen_tts":
                    self.assertEqual("BLOCKED", result["cuda"][0])

    def test_torch_torchaudio_and_cuda_runtime_must_match(self):
        for values, code in (
            (dict(audio_version="2.8.0+cu130"), "torch_version_mismatch"),
            (dict(audio_version="2.9.1+cu130"), "torch_version_mismatch"),
            (dict(audio_version="2.9.2+cpu"), "cuda_runtime_mismatch"),
            (dict(cuda_version=None), "cuda_runtime_unavailable"),
            (dict(cuda_version="12.8"), "cuda_runtime_mismatch"),
            (dict(torch_version="not-version"), "torch_version_mismatch"),
        ):
            with self.subTest(values=values):
                result, _, cuda = self.child_checks(**values)
                self.assertEqual(("FAIL", code), result["torch"])
                cuda.is_available.assert_not_called()

    def test_cuda_requires_available_exact_gpu_and_profile_vram(self):
        for values, status, code in (
            (dict(available=False), "BLOCKED", "cuda_unavailable"),
            (dict(name="NVIDIA GeForce RTX 5090"), "BLOCKED", "cuda_device_mismatch"),
            (dict(name="NVIDIA GeForce RTX 5090 D"), "BLOCKED", "cuda_device_mismatch"),
            (dict(memory=23 * 1000 ** 3), "BLOCKED", "insufficient_vram"),
            (dict(minimum=32), "BLOCKED", "insufficient_vram"),
            (dict(memory=float("nan")), "FAIL", "cuda_probe_failed"),
        ):
            with self.subTest(values=values):
                result, _, _ = self.child_checks(**values)
                self.assertEqual((status, code), result["cuda"])

    def test_cuda_vram_uses_decimal_gb_without_rounding_up(self):
        for memory, minimum, expected in (
            (24_000_000_000, 24, ("PASS", "ok")),
            (int(23.99 * 1024 ** 3), 24, ("PASS", "ok")),
            (23_999_999_999, 24, ("BLOCKED", "insufficient_vram")),
            (32_000_000_000, 32, ("PASS", "ok")),
            (31_999_999_999, 32, ("BLOCKED", "insufficient_vram")),
        ):
            with self.subTest(memory=memory, minimum=minimum):
                result, _, _ = self.child_checks(memory=memory, minimum=minimum)
                self.assertEqual(expected, result["cuda"])

    def test_module_import_does_not_import_gpu_packages(self):
        actual_import = __import__
        def guarded(name, *args, **kwargs):
            if name.split(".")[0] in {"torch", "torchaudio", "qwen_tts"}:
                self.fail("Agent must not import model packages")
            return actual_import(name, *args, **kwargs)
        with patch("builtins.__import__", side_effect=guarded):
            importlib.reload(self.probe)


class BoundedProcessTest(ProbeTestCase):
    def test_reaped_nonzero_child_is_not_tree_killed_again(self):
        # Windows taskkill fails when the root was already reaped; that is not a
        # cleanup failure after EOF + a completed wait.
        process = Mock(pid=4321, stdout=io.BytesIO(b"private"))
        process.wait.return_value = 3
        process.poll.return_value = 3
        with patch.object(self.probe, "_stop_process_tree", return_value=False) as stop:
            result = self.probe.run_bounded(["doubled"], timeout=1, max_output_bytes=64,
                                           popen_factory=lambda *_a, **_k: process)
        self.assertEqual(("probe_failed", b""), (result.code, result.output))
        stop.assert_not_called()

    def test_closed_pipes_do_not_bypass_child_exit_deadline(self):
        result = self.run_python("import os,time; os.close(1); os.close(2); time.sleep(30)",
                                 timeout=0.1, max_output_bytes=64)
        self.assertEqual(("probe_timeout", b""), (result.code, result.output))

    def test_failed_cleanup_propagates_fixed_status(self):
        process = Mock(pid=4321, stdout=io.BytesIO(b""))
        process.wait.side_effect = subprocess.TimeoutExpired("private", 1)
        with patch.object(self.probe, "_stop_process_tree", return_value=False):
            result = self.probe.run_bounded(["doubled"], timeout=1, max_output_bytes=64,
                                           popen_factory=lambda *_a, **_k: process)
        self.assertEqual(("probe_cleanup_failed", b""), (result.code, result.output))

    def test_unexpected_wait_failure_still_cleans_process_and_hides_exception(self):
        process = Mock(pid=4321, stdout=io.BytesIO(b"private"))
        process.wait.side_effect = OSError("/private/wait")
        with patch.object(self.probe, "_stop_process_tree", return_value=True) as stop:
            result = self.probe.run_bounded(["doubled"], timeout=1, max_output_bytes=64,
                                           popen_factory=lambda *_a, **_k: process)
        self.assertEqual(("probe_failed", b""), (result.code, result.output))
        stop.assert_called_once_with(process)
        self.assertTrue(process.stdout.closed)

    def test_taskkill_failure_is_not_reported_as_successful_tree_cleanup(self):
        for behavior in (OSError("/private/taskkill"), subprocess.CompletedProcess([], 1)):
            with self.subTest(behavior=type(behavior).__name__):
                process = Mock(pid=4321)
                process.poll.return_value = 0
                runner = Mock()
                if isinstance(behavior, Exception):
                    runner.side_effect = behavior
                else:
                    runner.return_value = behavior
                self.assertFalse(self.probe._stop_process_tree(
                    process, platform_name="nt", command_runner=runner))

    def test_python_probe_process_is_offline_and_has_no_shell_or_stdin(self):
        process = Mock(pid=4321, stdout=io.BytesIO(b"ok"))
        process.wait.return_value = 0
        factory = Mock(return_value=process)
        result = self.probe.run_bounded(["doubled"], timeout=1, max_output_bytes=64,
                                       popen_factory=factory)
        self.assertEqual("ok", result.code)
        options = factory.call_args.kwargs
        self.assertFalse(options["shell"])
        self.assertEqual(subprocess.DEVNULL, options["stdin"])
        self.assertEqual(subprocess.STDOUT, options["stderr"])
        self.assertEqual("1", options["env"]["HF_HUB_OFFLINE"])
        self.assertEqual("1", options["env"]["TRANSFORMERS_OFFLINE"])

    def run_python(self, source, **kwargs):
        processes = []
        def start(*args, **options):
            process = subprocess.Popen(*args, **options)
            processes.append(process)
            return process
        output = self.probe.run_bounded(
            [sys.executable, "-I", "-B", "-c", source],
            popen_factory=start, **kwargs,
        )
        self.assertEqual(1, len(processes))
        self.assertIsNotNone(processes[0].poll())
        self.assertTrue(processes[0].stdout.closed)
        return output

    def test_success_captures_both_streams_without_echo(self):
        captured = io.StringIO()
        with contextlib.redirect_stdout(captured), contextlib.redirect_stderr(captured):
            result = self.run_python("import os; os.write(1,b'one'); os.write(2,b'two')",
                                     timeout=2, max_output_bytes=64)
        self.assertEqual("ok", result.code)
        self.assertEqual(b"onetwo", result.output)
        self.assertEqual("", captured.getvalue())

    def test_timeout_kills_and_reaps_owned_child(self):
        began = time.monotonic()
        result = self.run_python("import time; time.sleep(30)", timeout=0.1, max_output_bytes=64)
        self.assertEqual("probe_timeout", result.code)
        self.assertEqual(b"", result.output)
        self.assertLess(time.monotonic() - began, 5)

    def test_output_flood_is_capped_and_terminated(self):
        result = self.run_python("import os,time; os.write(2,b'x'*1000000); time.sleep(30)",
                                 timeout=2, max_output_bytes=64)
        self.assertEqual("probe_output_limit", result.code)
        self.assertEqual(b"", result.output)

    def test_nonzero_exit_and_launch_error_never_return_raw_output(self):
        result = self.run_python("import sys; print('/private/token'); sys.exit(3)",
                                 timeout=2, max_output_bytes=64)
        self.assertEqual(("probe_failed", b""), (result.code, result.output))
        with patch.object(self.probe.subprocess, "Popen", side_effect=OSError("/private/token")):
            result = self.probe.run_bounded(["not-a-program"], timeout=1, max_output_bytes=64)
        self.assertEqual(("probe_unavailable", b""), (result.code, result.output))

    def test_windows_tree_cleanup_has_timeout_and_never_uses_image_name(self):
        process = Mock(pid=4321)
        process.poll.return_value = None
        process.wait.return_value = 0
        command = Mock()
        self.probe._stop_process_tree(process, platform_name="nt", command_runner=command)
        argv = command.call_args.args[0]
        self.assertEqual(["taskkill", "/PID", "4321", "/T", "/F"], argv)
        self.assertGreater(command.call_args.kwargs["timeout"], 0)
        self.assertEqual(subprocess.DEVNULL, command.call_args.kwargs["stdout"])
        self.assertEqual(subprocess.DEVNULL, command.call_args.kwargs["stderr"])


if __name__ == "__main__":
    unittest.main()
