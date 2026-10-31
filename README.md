<img src="src/main/resources/META-INF/pluginIcon.svg" alt="rr plugin logo" width="96" align="right">

# rr Record and Replay for CLion

Record a run with [rr](https://rr-project.org), replay it in CLion's normal debugger, and move backwards
(reverse-continue, reverse-step, reverse-next, reverse-finish). Linux only; on other systems the plugin loads
and stays inert.

> **Status:** Record and Replay were confirmed working in CLion 2026.2.4 by manual test; the remaining items in the
> manual test script below are not yet reported. It compiles against CLion 2026.2.4, passes unit tests,
> `buildPlugin` and `verifyPlugin`, and the rr/GDB protocol it relies on was exercised with a plain `gdb`
> client (see [docs/spike-notes.md](docs/spike-notes.md)). The IDE behaviour needs the manual test below.

## Features

- **Record with rr** – an executor next to Run/Debug. Runs the selected CMake configuration under `rr record`;
  output goes to the Run window. Checks `perf_event_paranoid` first and offers `pkexec sysctl -w
  kernel.perf_event_paranoid=1` (with explicit confirmation), or shows/copies the command if `pkexec` is missing.
- **Replay with rr** – an executor next to Run/Debug/Record (works on any CMake configuration or an "rr Replay"
  configuration), plus **Replay with rr…** in the Run menu – pick the latest trace (default), any trace from `rr ls`, or a directory.
  Starts `rr replay -s <port> -k`, then opens a regular GDB debug session connected to it.
- **Reverse controls** in the Debug window toolbar, plus *Show Current rr Event* (`when`) and *Go to rr Event*
  (`run N`, see limits).
- **Settings** (Settings | Tools | rr): rr binary, trace directory (default honours `_RR_TRACE_DIR`), extra
  `rr record` flags such as `-h` (chaos mode).

## Requirements

Linux, rr ≥ 5.0 with working hardware performance counters, GDB (CLion's bundled or a custom one), a *local*
toolchain, CLion 2026.1 or 2026.2 (builds 261-262; 2026.1 passes the plugin verifier but is untested at runtime).

## Build

```
./gradlew test buildPlugin verifyPlugin     # JDK 21
./gradlew runIde                            # sandbox CLion
```

CI (GitHub Actions): `ci.yml` runs tests and builds on pull requests and pushes to `master` (plus `verifyPlugin` on `master`);
pushing a tag like `v0.2.0` runs `release.yml`, which builds with that version and attaches the zip to a GitHub release.
`./build-plugin.sh [version]` does the same build locally.

Layout: `core/` (platform-only: rr discovery, perf check, trace listing, ports, settings), `clion/` (CIDR
part; every unofficial CIDR call lives in `clion/.../cidr/CidrBridge.kt`), root (plugin.xml, packaging).
`core` must not import CIDR; a unit test enforces this.

## Manual test script

1. Open a CMake project containing a small program, e.g. a loop calling `int sq(int)` three times.
   Select its run configuration.
2. Choose **Record with rr** (next to the Debug button). If prompted about `perf_event_paranoid`, confirm and
   authenticate. Expect the program's output in the Run window and a new trace (`rr ls`).
3. Run → **Replay with rr…** → *Latest recording*. A debug session opens; the program stops at the start.
4. Set a breakpoint inside `sq`, press Resume: it stops at the first call. Variables/frames show.
5. Press **Reverse Continue**: execution goes back to an earlier stop. **Reverse Step Into/Over/Out** move
   the editor line backwards; frames and variables must refresh each time.
6. Record again, then Replay → pick the *older* trace from the list; confirm it replays the older run
   (also try *Choose a trace directory…*).
7. Run configuration "rr Replay": check the same via Run | Edit Configurations, and *Show Current rr Event*.

## Known limits

- For a Google Test (or other CLion test) configuration, "Replay with rr" shows the test-results tree next to the
  console by feeding the replayed output to it. Untested; the tree fills as the replay runs forward and
  reverse execution can re-emit output, so counts may look odd after rewinding.
- GDB prints `warning: BFD: ... system-supplied DSO at 0x6fffd000 ...` and `Discarding section .replay.text ...` on connect.
  This is rr's replacement vDSO (a stub page it injects); it is harmless and can be ignored.
- Local toolchains only. Remote, Docker, SSH and WSL toolchains show a "not supported" message.
- CLion's remote GDB driver issues `target remote`, not `extended-remote`, so **Go to rr Event** (`run N`) and
  rr's `restart` are rejected by GDB. Reverse execution, breakpoints and `when` work.
- GDB only. An LLDB toolchain is not detected (the API for that is being removed); replay still uses GDB.
- The replayed program's stdout comes from the rr server process and is forwarded to the debug console; a piped
  stdout is buffered by the program until it exits.
- Traces that are incomplete (interrupted recordings) cannot be replayed.
- Uses unofficial CIDR APIs; a CLion update may require changes in `CidrBridge.kt` only.
- The `perf_event_paranoid` fix lasts until reboot; for a permanent change put
  `kernel.perf_event_paranoid = 1` in `/etc/sysctl.d/10-rr.conf`.
