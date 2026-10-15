# vibeplayer

## External agent CLIs

Delegate to `agy` from the shell. Call the binary by absolute path (the Bash tool runs zsh, where the
fish wrapper doesn't exist). `codex` may be used again (user's instruction, 2026-10-12, replacing the
2026-10-11 "do not use codex"): `codex exec "<task>"`; if it reports a usage limit, tell the user, who
resets it. Observed limit text: `ERROR: You've hit your usage limit ... try again at <date>`.

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

### cline (DeepSeek) — `/home/rava/.bun/bin/cline`

User's choice for faster responses than agy (2026-10-15): DeepSeek V4.1 Flash via Cline CLI 2.17.

```sh
cline -y --json --auto-condense -c <dir> "<task>" < /dev/null > <scratchpad>/cline_<task>_<n>.json 2>&1
```

- Cline labels the model `deepseek-chat`; the DeepSeek API serves that id with `deepseek-flash`
  (checked 2026-10-15), so no `-m` is needed. API models: `deepseek-flash`, `deepseek-v4-pro`.
- 128K context: give it small, single-goal tasks; `--auto-condense` for longer ones.
- Same rules as agy: AGENT_RULES.md and tools/agents/standing_rules.txt in the prompt; review its commits.

## Usage limits: stop and tell the user

The user has 3 Antigravity accounts and switches between them manually. Accounts are managed with
`aisw`; as of 2026-10-11 only one Antigravity profile (`first`) is stored there.

- agy: the limit error format has not been observed yet. Treat a JSON `status` other than `SUCCESS`,
  a non-zero exit, or usage/quota/rate-limit wording in `response` or stderr as a limit hit.
- **Exception granted 2026-10-12 (user going to sleep, overnight run):** if the current agy account hits
  its limit, Claude may log agy out and log in with the user's "arvid" account, whose OAuth session is
  already set up in the Brave browser (origin profile). This covers that one switch only; for any
  later limit hit, the rule below applies again unless the user says otherwise.
- **Permission granted 2026-10-14 for the overnight run (until 06:45 on 2026-10-15):** when an agy account
  hits its limit, Claude may switch between the user's "arvid" and "sarbast" Antigravity accounts as
  often as needed (`aisw use antigravity <profile>`), using profiles the user stored in aisw.
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

## Parallel agents

The user allows several agy runs at once (2026-10-11) as long as the tasks don't depend on unfinished
ones. Each parallel agent gets its own git worktree under `/home/rava/vibeplayer-worktrees/<name>` on a
branch `par/<name>`, with its task list declared under "Parallel tracks" in TASKS.md; Claude merges the
branch into `main` after review. All runs draw on the same agy account quota.
