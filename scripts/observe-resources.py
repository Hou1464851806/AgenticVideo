#!/usr/bin/env python3
"""Run a check while sampling Docker memory and task-owned scratch usage."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import threading
import time

parser = argparse.ArgumentParser()
parser.add_argument("--container", action="append", required=True)
parser.add_argument("--worker", required=True)
parser.add_argument("--output", required=True)
parser.add_argument("command", nargs=argparse.REMAINDER)
args = parser.parse_args()
command = args.command[1:] if args.command[:1] == ["--"] else args.command
if not command:
    parser.error("A check command is required")
stop = threading.Event()
peaks = {name: 0 for name in args.container}
samples = 0
scratch = 0

def memory_bytes(text):
    match = re.fullmatch(r"([\d.]+)(B|KiB|MiB|GiB|kB|MB|GB)", text.strip())
    if not match:
        return 0
    scales = {"B": 1, "KiB": 1024, "MiB": 1024**2, "GiB": 1024**3, "kB": 1000, "MB": 1000**2, "GB": 1000**3}
    return int(float(match[1]) * scales[match[2]])

def sample():
    global samples, scratch
    while not stop.is_set():
        try:
            result = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{json .}}", *args.container], capture_output=True, text=True, timeout=15)
            for line in result.stdout.splitlines():
                data = json.loads(line)
                name = data["Name"]
                peaks[name] = max(peaks.get(name, 0), memory_bytes(data["MemUsage"].split("/")[0]))
            disk = subprocess.run(["docker", "exec", args.worker, "du", "-sb", "/work"], capture_output=True, text=True, timeout=10)
            if disk.returncode == 0:
                scratch = max(scratch, int(disk.stdout.split()[0]))
            samples += 1
        except (subprocess.TimeoutExpired, ValueError, KeyError):
            pass
        stop.wait(2)

thread = threading.Thread(target=sample)
start = time.monotonic()
thread.start()
try:
    result = subprocess.run(command)
finally:
    stop.set()
    thread.join()
report = {"elapsed_seconds": round(time.monotonic() - start, 2), "sampled_peak_memory_bytes": peaks, "sampled_peak_worker_scratch_bytes": scratch, "samples": samples, "exit_code": result.returncode, "sampling": "docker stats and du; approximately 2–5 second intervals, not a guaranteed instantaneous peak"}
Path(args.output).write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report))
raise SystemExit(result.returncode)
