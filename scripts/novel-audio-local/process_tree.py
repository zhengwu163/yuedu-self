import os
import signal
import subprocess
import time


def wait_for_exit(process, timeout):
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        return False
    return process.poll() is not None


def terminate_process_tree(
    process,
    timeout,
    command_runner=subprocess.run,
    platform_name=os.name,
):
    if process.poll() is not None:
        return True
    process.terminate()
    if wait_for_exit(process, timeout):
        return True
    pid = int(process.pid)
    if platform_name == "nt":
        try:
            command_runner(
                ["taskkill", "/PID", str(pid), "/T", "/F"],
                check=False,
                capture_output=True,
                text=True,
            )
        except TypeError:
            command_runner(["taskkill", "/PID", str(pid), "/T", "/F"])
    else:
        os.killpg(os.getpgid(pid), signal.SIGTERM)
    if wait_for_exit(process, timeout):
        return True
    if platform_name != "nt":
        os.killpg(os.getpgid(pid), signal.SIGKILL)
    return wait_for_exit(process, timeout)


def process_is_gone(pid, process_probe):
    try:
        return not bool(process_probe(int(pid)))
    except (OSError, ProcessLookupError):
        return True
