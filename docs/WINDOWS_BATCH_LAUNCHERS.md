# Windows double-click launchers

From a downloaded or cloned ChordFlow checkout, double-click **Start-ChordFlow.bat** in the repository root. It runs the build and self-tests, opens separate controller and worker consoles, and opens http://127.0.0.1:8791 in the default browser.

Other optional launchers at the repository root:

- **Build-ChordFlow.bat** — check prerequisites, compile Java and run self-tests.
- **Start-Controller.bat** — run just the controller/dashboard.
- **Start-Worker.bat** — run just the worker, after the controller has started.

Prerequisites: Windows 10/11, JDK **21** with `java` and `javac` on PATH, and FFmpeg/`ffprobe` on PATH. The launchers use Windows PowerShell with a **process-scoped** ExecutionPolicy Bypass, without changing your machine's persistent policy. All commands work relative to the folder containing the .bat files (including paths containing spaces).

Job outputs: `chordflow-data/output/`. Keep the controller and worker command windows open while jobs run. Before shutting down, finish/drain active jobs through the dashboard. Closing the windows during a render may interrupt the job; the controller should recover leases when restarted but full crash-recovery is not production-certified.

**Troubleshooting:** If the initial build fails, the starter stops without launching the controller or worker. Read the open batch console for the first missing prerequisite or Java test failure. If the browser is opened before the controller is listening, refresh after the controller window displays its startup output. To inspect a failure individually, run `Build-ChordFlow.bat`, then `Start-Controller.bat`, then `Start-Worker.bat`.

These launchers wrap the existing `scripts/*.ps1` implementation; no new ComfyUI, TTS, Ollama or soundfont dependencies are introduced.
