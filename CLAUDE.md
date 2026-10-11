# vibeplayer

## External agent CLIs

Delegate to `agy` from the shell. Call the binary by absolute path (the Bash tool runs zsh, where the
fish wrapper doesn't exist). Do not use `codex` in this project (user's instruction, 2026-10-11).

### agy (Antigravity CLI) — `/home/rava/.local/bin/agy`

```sh
/home/rava/.local/bin/agy -p "<prompt>" --mode accept-edits --output-format json
```

- Run from the project root; `--mode accept-edits` lets it write files headlessly (verified).
- Output is one JSON object: `{"status":"SUCCESS","response":"...","conversation_id":"...","usage":{...}}`.
- Continue a thread with `--conversation <id>`; pick a model with `--model` (`agy models` lists them,
  e.g. `gemini-3.1-pro-high`, `gemini-3.8-flash-high`).
- Don't use `--dangerously-skip-permissions` unless the user asks.

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
