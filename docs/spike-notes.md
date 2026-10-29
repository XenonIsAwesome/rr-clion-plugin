# Spike notes

Environment: Linux 6.8, rr 5.7.0, gdb (system), CLion SDK 2026.2.4 (build 262.11879.74) as the Gradle
compile target. **CLion itself was never started** (no display/IDE available here), so every statement
about CLion's runtime behaviour below is tagged *reasoned*, not *verified*.

Legend: **VERIFIED** = I ran it. **API-CHECKED** = confirmed by `javap`/bytecode of the 2026.2.4 SDK and a
successful compile + `verifyPlugin`. **REASONED** = inferred, needs a manual test.

## Spike 1 – Replay Option A (`rr replay -s <port> -k` + GDB client)

Outcome: **viable; chosen.**

Verified with a plain `gdb` client against a real recording (`rr record` of a 3-iteration C program):

- `rr replay -s <port> -k <trace>` listens on the port; stderr then contains a block
  `Launch gdb with\n  gdb '-l' '10000' '-ex' 'set sysroot /' '-ex' 'target extended-remote 127.0.0.1:PORT' <exe>`.
  The `<exe>` is unquoted and is rr's copy of the binary inside the trace (`mmap_copy_N_<name>`). The plugin
  parses this line (`RrLaunchLine`) and uses it as the symbol file, so replays still work after a rebuild.
- Readiness: polling `/proc/net/tcp` for a LISTEN socket works (~0.2 s). **Do not probe by connecting**:
  rr hands the first connection to the debugger, so a probe would steal it.
- Breakpoints bind (`break sq` → `Breakpoint 1, sq (x=1)`), `continue`, `info locals`, `reverse-continue`,
  `reverse-step`, `reverse-next`, `reverse-finish`, `when`, `detach` all work with `source <rr gdbinit>` +
  `set sysroot /` + `target extended-remote`.
- With plain `target remote` (which is what CLion's GDB driver issues, see below) the same breakpoint /
  reverse / `when` commands work, but **`run <event>` fails**: `The "remote" target does not support "run"`.
- `rr gdbinit` output is noisy in batch mode (`Really redefine built-in command "restart"?` auto-answered,
  Python `SyntaxWarning`s). Harmless.
- The replayed program's stdout is written by the **rr server process**, not by gdb. With stdout as a pipe
  the program's libc buffers until exit, so output only appears once replay reaches the end.

API-CHECKED (CLion 2026.2.4):

- The Remote Debug run configuration is implemented by `CidrRemoteGDBDebugProcess(driverConfig,
  CidrRemoteDebugParameters, session, consoleBuilder)` with a `CLionGDBDriverConfiguration(project, toolchain)`
  (see `RemoteLauncher.createDebugProcess` in `intellij.clion.execution.jar`). The plugin builds exactly that
  process inside its own `XDebugProcessStarter`; no custom `DebuggerDriver`.
- `CidrRemoteDebugParameters` has `remoteCommand`, `symbolFile`, `sysroot`, `additionalCommands`.
  Pre-connect commands **can** be injected: `sysroot = "/"` and `additionalCommands = ["source <rr gdbinit file>"]`.
  Caveat: `additionalCommands` run after the connection is made (REASONED), which is fine for the macros.
- `GDBDriver` connects with MI `-target-select remote %s`, i.e. `remote`, not `extended-remote`.
  Consequence: "Go to rr Event" (`run N`) is best-effort and will report an error. Everything else works in
  `remote` mode.

REASONED / needs manual test: that breakpoints set in the editor bind, that variables and stack render, and that
the rr server's stdout appears in the Debug console (the plugin forwards it via `session.consoleView`).
Open risk: if `sysroot = "/"` is mapped to something other than `set sysroot /`, shared-library symbols may not
load; fallback is `additionalCommands += "set sysroot /"`.

### Spike 1 follow-up: confirmed in CLion 2026.2.4

Manually confirmed by the user in CLion: Record with rr works, and Replay with rr opens a working GDB session.
Lesson learned from the debug log: the `CidrRemoteGDBDebugProcess` must be `start()`ed by the caller (CLion's own
`CidrLauncher.startDebugProcess` does it on the EDT). Without it GDB is spawned but never loads the executable or
connects, and every breakpoint fails with "No symbol table is loaded". Reverse-button refresh (spike 3) and the
other REASONED items below were not reported on separately.

## Spike 2 – Replay Option B (custom GDB wrapper)

Not built, not run. REASONED: CLion validates the GDB binary (version check by running `gdb --version`), and
drives it with `-exec-run`; with `exec rr replay -- "$@"` the start-up sequence would race rr's own gdb launch,
and the setting is toolchain-wide. Kept as a documented fallback only.

## Spike 3 – Do reverse commands refresh the UI?

Mechanism (API-CHECKED): actions call `CidrDebugProcess.postCommand(SuspendedDebuggerCommand { driver ->
driver.executeInterpreterCommand("reverse-step") })`. The GDB/MI driver sends the command through
`-interpreter-exec console`, and GDB answers a stepping command with an async `*stopped` record, the same
record a forward step produces. **REASONED**: CLion's `handleStopped` path therefore refreshes frames,
variables and the editor position; the existing zerolfx plugin relies on this. **Not verified.** If the UI
does not refresh, the fallback is to post `-stack-info-frame`/`frame` as a follow-up through the same
`postCommand`, or call `CidrDebugProcess.getSession().rebuildViews()`-style APIs (not investigated).

## Spike 4 – Frontend/backend split (2026.1+)

REASONED only. Actions are registered in `XDebugger.ToolWindow.TopToolbar`, `TopToolbar3`, `LeftToolbar`
with `ActionUpdateThread.BGT`, and look the session up via `XDebugSession.DATA_KEY` with
`XDebuggerManager.currentSession` as fallback. `verifyPlugin` reports "Plugin can probably be enabled or
disabled without IDE restart". Whether the toolbar actions appear and bind to the right session in a remote-dev
split setup needs a manual check.

## Spike 5 – 2026.2 "Debug Profiles" / generic GDB Server profile

Not investigated. The 2026.2 SDK does contain `CidrDebugProfile` and `RemoteDebugProfileLauncher`
(API-CHECKED by class listing); evaluating them as a replacement for the custom starter is future work.

## Other findings

- `rr ls -t -r` lists **newest first** and includes the `latest-trace` symlink as an entry (dropped by the parser).
  `rr ls -t` alone is oldest first. `rr ls -l` aborts with no output if *any* trace is incomplete, so the plugin
  uses the names-only form and marks incomplete traces itself (presence of an `incomplete` file).
- Command-line wrapping for "Record with rr": the forum suggestion works against the current SDK in a cleaner form
  than `RunConfigurationExtensionBase`: CLion has its own EP `cidr.runConfigurationExtension`
  (`CidrRunConfigurationExtensionBase`) whose `patchCommandLine(..., runnerId, ...)` receives the id of the
  `ProgramRunner` that started the run (API-CHECKED in `CLionLauncher.createProcess`). The plugin wraps the
  command only when `runnerId == "RrRecordRunner"`.
