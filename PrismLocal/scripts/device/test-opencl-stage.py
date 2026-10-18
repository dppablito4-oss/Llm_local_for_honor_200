"""Run one experimental OpenCL stage; retain telemetry and stop at failures.

Requires the separate DevAdreno APK and its androidTest APK already installed.
No model downloads, package uninstall, data clear, root or system changes.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("stage", choices=["runtime", "math", "cpu", "gpu", "clip", "unet", "vae"])
p.add_argument("--serial", required=True)
p.add_argument("--package", default="com.prismai.llmhost.dev.adreno")
p.add_argument("--hybrid", action="store_true")
p.add_argument("--output", type=Path, default=Path("build/opencl-experiment/device"))
a = p.parse_args()
if not re.fullmatch(r"[A-Za-z0-9_.]+", a.package):
    p.error("invalid application package")
adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
base = [str(adb), "-s", a.serial]
a.output.mkdir(parents=True, exist_ok=True)
name = a.stage + ("-hybrid" if a.hybrid else "-all")
cancel_phase = a.stage.upper() if a.stage in ("clip", "unet", "vae") else None
methods = {"runtime": "runtimeEnumeratesGpu", "math": "ggmlMatrixMatchesCpu",
           "cpu": "sdxsImageAndRecovery", "gpu": "sdxsImageAndRecovery"}
method = methods.get(a.stage, "cancelAtObservedPhaseAndRecoverOnCpu")
args = ["shell", "am", "instrument", "-w", "-r", "-e", "class",
        f"com.prismai.llmhost.OpenClDeviceTest#{method}"]
if a.stage in ("cpu", "gpu"):
    args += ["-e", "diffusion_backend", "CPU" if a.stage == "cpu" else "GPU_OPENCL"]
if a.hybrid:
    args += ["-e", "hybrid", "true"]
if cancel_phase:
    args += ["-e", "cancel_phase", cancel_phase]
args += [a.package + ".test/androidx.test.runner.AndroidJUnitRunner"]
observed = threading.Event()
receiver_ready = threading.Event()
stop_reader = threading.Event()
phase_line = []
phase_pid = []
received_phase = []
sent_cancel = threading.Event()
cancel_errors = []
patterns = {"CLIP": r"clip executing segment", "UNET": r"unet executing segment",
            "VAE": r"(?:vae|taesd) executing segment"}

with (a.output / f"{name}-logcat.txt").open("w", encoding="utf-8") as logs:
    logcat = subprocess.Popen(base + ["logcat", "-T", "1", "-v", "threadtime", "-b", "main", "-s",
        "PrismGPU", "PrismOpenCL", "OpenCLLoader", "PrismDiffusion", "PrismDiffusionNative", "TestRunner", "AndroidRuntime", "libc"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")

    def read_logs():
        last_phase = None
        for line in logcat.stdout:
            if stop_reader.is_set():
                break
            logs.write(line)
            logs.flush()
            if cancel_phase and f"receiver ready: phase={cancel_phase}" in line:
                receiver_ready.set()
                match = re.match(r"\S+\s+\S+\s+(\d+)\s+", line)
                if match:
                    phase_pid.append(match[1])
            same_pid = bool(phase_pid and re.match(r"\S+\s+\S+\s+" + phase_pid[0] + r"\s+", line))
            if same_pid:
                for phase, pattern in patterns.items():
                    if re.search(pattern, line, re.I):
                        last_phase = phase
                if "get_learned_condition completed" in line:
                    last_phase = "POST_CLIP"
                elif "sampling completed" in line:
                    last_phase = "POST_UNET"
                if "Test cancellation received:" in line:
                    received_phase.append(last_phase)
            if cancel_phase and receiver_ready.is_set() and same_pid and re.search(patterns[cancel_phase], line, re.I):
                if not observed.is_set():
                    phase_line.append(line.strip())
                    observed.set()
                    def send_cancel():
                        try:
                            subprocess.run(base + ["shell", "am", "broadcast", "-a", a.package + ".TEST_CANCEL_DIFFUSION",
                                "-p", a.package, "--es", "phase", cancel_phase], check=True, capture_output=True, timeout=10)
                            sent_cancel.set()
                            print("Cancellation sent after:", phase_line[0], flush=True)
                        except Exception as error:
                            cancel_errors.append(str(error))
                    threading.Thread(target=send_cancel, daemon=True).start()

    reader = threading.Thread(target=read_logs, daemon=True)
    reader.start()
    telemetry = []
    forced_stop = False
    started = time.monotonic()
    # Image watchdog cancels at 180s in-app. Allow cleanup, then stop this
    # experimental package only if a driver call cannot return.
    deadline = 120 if a.stage in ("runtime", "math") else 240
    try:
        with (a.output / f"{name}-instrumentation.txt").open("w", encoding="utf-8") as out:
            process = subprocess.Popen(base + args, stdout=out, stderr=subprocess.STDOUT,
                                       text=True, encoding="utf-8", errors="replace")
            while process.poll() is None:
                try:
                    thermal = subprocess.run(base + ["shell", "dumpsys", "thermalservice"],
                        capture_output=True, text=True, timeout=5).stdout
                    status = re.search(r"^Thermal Status: (\d+)", thermal, re.M)
                    temperatures = re.findall(r"Temperature\{mValue=([^,]+), mType=(\d+), mName=([^,]+), mStatus=(\d+)\}", thermal)
                    telemetry.append({"elapsed_seconds": time.monotonic() - started,
                        "android_thermal_status": int(status[1]) if status else None,
                        "temperatures": [{"celsius": float(t), "type": int(k), "name": n, "status": int(s)}
                            for t, k, n, s in temperatures if int(k) in (0, 1, 2)]})
                except (subprocess.TimeoutExpired, ValueError):
                    telemetry.append({"elapsed_seconds": time.monotonic() - started, "measurement_failed": True})
                if time.monotonic() - started > deadline:
                    forced_stop = True
                    subprocess.run(base + ["shell", "am", "force-stop", a.package], check=True, timeout=10)
                    print("Controlled stop of experimental package: stage deadline exceeded", flush=True)
                    break
                time.sleep(0.25 if cancel_phase else 1)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=10)
    finally:
        stop_reader.set()
        logcat.terminate()
        try:
            logcat.wait(timeout=10)
        except subprocess.TimeoutExpired:
            logcat.kill()
        reader.join(timeout=5)
    (a.output / f"{name}-telemetry.json").write_text(json.dumps({"stage": a.stage,
        "operator_deadline_stop": forced_stop, "phase_observation": phase_line,
        "cancel_sent": sent_cancel.is_set(), "cancel_errors": cancel_errors,
        "phase_when_receiver_observed_request": received_phase, "samples": telemetry}, indent=2), encoding="utf-8")

report_name = {"runtime": "runtime", "math": "ggml-matrix", "cpu": "sdxs-CPU", "gpu": "sdxs-GPU_OPENCL"}.get(a.stage)
if a.stage in ("cpu", "gpu"):
    report_name += "-hybrid" if a.hybrid else "-all"
if cancel_phase:
    report_name = "cancel-" + cancel_phase
reports = a.output / "reports"
reports.mkdir(exist_ok=True)
remote = f"/sdcard/Android/data/{a.package}/files/opencl-verification/"
local_report = reports / (report_name + ".json")
pulled = subprocess.run(base + ["pull", remote + local_report.name, str(local_report)],
    check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
if pulled.returncode == 0:
    data = json.loads(local_report.read_text(encoding="utf-8"))
    for key in ("png_path", "cpu_png_path"):
        if data.get(key):
            png_name = Path(data[key]).name
            subprocess.run(base + ["pull", remote + png_name, str(reports / png_name)],
                check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
transcript = (a.output / f"{name}-instrumentation.txt").read_text(encoding="utf-8")
passed = (not forced_stop and process.returncode == 0 and re.search(r"OK \(1 test\)", transcript)
          and not re.search(r"FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[234]", transcript))
print(transcript, flush=True)
if cancel_phase and (not sent_cancel.is_set() or received_phase != [cancel_phase]):
    passed = False
if not passed:
    raise SystemExit("FAIL: stage not verified; preserve evidence and do not proceed to the next stage")
print("PASS:", a.stage)
