# ChordFlow Windows one-click launcher

## Recommended: one file, like AutoNewsRoller

Double-click **command_center_windows.bat** from the main ChordFlow directory. You do **not** need to start the controller and worker separately.

The launcher:
1. Changes to the ChordFlow folder (works when the path contains spaces).
2. Checks Java 21, FFmpeg and FFprobe; compiles the source and runs Java self-tests.
3. Detects whether a ChordFlow command center is already serving **http://127.0.0.1:8791**.
4. If it is not running, starts the controller **in the foreground of the launcher window** (AutoNewsRoller style).
5. After the controller responds, starts a separate worker console, or reuses an existing ChordFlow Java worker.
6. Opens the Command Center in the default browser.

The controller command window stays open. The worker command window stays open. The temporary companion used to coordinate worker/browser startup exits automatically. Re-running the launcher reuses the controller and worker rather than starting duplicate controller/worker sessions.

**Alternate old name:** Start-ChordFlow.bat calls the recommended command_center_windows.bat.

## Requirements

Windows 10/11, JDK **21** with java and javac on PATH, and FFmpeg/ffprobe on PATH. No Ollama, ComfyUI, TTS, Python package or soundfont is required for the built-in music generator.

This launcher invokes PowerShell using **process-scoped** ExecutionPolicy Bypass and does not change the machine's persistent execution-policy settings.

## URLs and files

- Command Center: http://127.0.0.1:8791
- Generated MP4s, MIDI, manifests: chordflow-data/output/
- Persistent job state: chordflow-data/jobs/
- Windows startup diagnostics: chordflow-data/windows-launcher.log

## Stop/restart safely

Use **Drain** in the dashboard to finish currently active jobs before closing the controller and worker windows. Closing mid-render can interrupt work; crash recovery is not fully production-certified.

If Java/FFmpeg or tests fail, the launcher stops before starting the controller/worker and prints the first error. The launcher will not claim that a controller started if the health check does not respond. If the companion has an error, check chordflow-data/windows-launcher.log and the worker console.

## Optional manual and diagnostic commands

- Build-ChordFlow.bat — build and run self-tests only
- Start-Controller.bat — run only the command center
- Start-Worker.bat — run only the worker
- command_center_windows.bat --preflight — build/test the prerequisites without starting services, useful for CI

For normal operation only **command_center_windows.bat** is needed.
