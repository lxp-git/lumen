# Lumen skill for AI coding agents

Lets Claude Code (or any agent that reads [Agent Skills](https://agentskills.io)) debug an
Android debug build that has Lumen: list and read captured HTTP / WebSocket / Socket.IO
traffic, search logcat history, read and edit SharedPreferences / DataStore / MMKV, run SQL
against SQLite / Room, dump the View tree, and add mock responses. It also knows how to add
Lumen to a Gradle project.

Everything goes through [`skills/lumen/scripts/lumen.py`](skills/lumen/scripts/lumen.py), a
single-file CLI (Python 3.8+, standard library only) that reaches the app's Chrome DevTools
Protocol endpoint over `adb`. Chrome is not needed.

## Install

**Claude Code plugin** (recommended; updates with `/plugin`):

```text
/plugin marketplace add lxp-git/lumen
/plugin install lumen@lumen
```

Claude Code 2.1.275+ also accepts `/plugin install lumen --marketplace lxp-git/lumen`.
From a shell: `claude plugin marketplace add lxp-git/lumen && claude plugin install lumen@lumen`.

**Plain skill** (no plugin system, or to pin a copy in a project):

```bash
git clone --depth 1 https://github.com/lxp-git/lumen /tmp/lumen
cp -R /tmp/lumen/plugins/lumen/skills/lumen ~/.claude/skills/lumen   # just you
cp -R /tmp/lumen/plugins/lumen/skills/lumen .claude/skills/lumen     # everyone on this repo
```

A symlink works too if you keep a checkout around. Other agents: point them at the
`skills/lumen` folder (`SKILL.md` plus `scripts/` and `references/`).

## Use

Ask in plain language; the skill triggers on Android debugging tasks:

- "Why does the login request fail on the emulator?"
- "Show the last WebSocket messages the chat screen received."
- "Set `onboarded` to false in SharedPreferences and tell me what the app logs on launch."
- "Make `/v1/orders` return a 500 so I can check the error state."
- "Add Lumen to this project."

Or call it directly: `/lumen:lumen` (plugin) or `/lumen` (plain skill). The CLI works on its
own as well:

```bash
python3 plugins/lumen/skills/lumen/scripts/lumen.py --help
```

Requirements: `adb` on `PATH` (or `ANDROID_HOME` / `ADB`), a device or emulator, and a debug
build of an app that applies the `io.github.lxp-git.lumen` Gradle plugin.
