# vibeplayer

## External agent CLIs

Delegate to `agy` from the shell. Call the binary by absolute path (the Bash tool runs zsh, where the
fish wrapper doesn't exist). Do not use `codex` in this project (user's instruction, 2026-10-11).

### agy (Antigravity CLI) — `/home/rava/.local/bin/agy`

```sh
/home/rava/.local/bin/agy --model gemini-3.8-flash --effort <low|medium|high> \
  --dangerously-skip-permissions --output-format json -p "<task>" > <scratchpad>/agy_<task>_<n>.json 2>&1
```

- This is the form the user asked for (2026-10-11). Their shorthand `flash.3.8` is not a valid model
  id; `gemini-3.8-flash` is the name that accepts `--effort`.
- The user approved `--dangerously-skip-permissions` for this project (2026-10-11), so agy runs its
  own git, builds and adb and commits itself. Without the flag, headless agy cannot run commands.
- Run long sessions in the background; review the commits afterwards.
- Android Studio: `/home/rava/.local/share/JetBrains/Toolbox/apps/android-studio` (JBR in `jbr/`).
- Output is one JSON object: `{"status":"SUCCESS","response":"...","denied_actions":[...],"usage":{...}}`.
  Continue a thread with `--conversation <id>`.

## Usage limits: stop and tell the user

The user has 3 Antigravity accounts and switches between them manually. Accounts are managed with
`aisw`; as of 2026-10-11 only one Antigravity profile (`first`) is stored there.

- agy: the limit error format has not been observed yet. Treat a JSON `status` other than `SUCCESS`,
  a non-zero exit, or usage/quota/rate-limit wording in `response` or stderr as a limit hit.
- When that happens: stop delegating to agy, tell the user it hit the limit and the
  reset time if one was printed, and wait for them to switch accounts. Do not switch accounts, add
  profiles, change models, or retry in a loop to get around it.
- Meanwhile, carry on with any work that doesn't need that tool.
- After the user says they've switched, re-run the failed call.

## Role in this project

Claude is the architect and reviewer; implementing agents write the code and run builds, following
AGENT_RULES.md (entry point: AGENTS.md, auto-loaded by agy). Planning files: SPEC.md, ARCHITECTURE.md,
TASKS.md, PROGRESS.md, BLOCKERS.md. When reviewing, read commits after "Last reviewed commit" in
PROGRESS.md, check them against the task's acceptance criteria, then update that line.
