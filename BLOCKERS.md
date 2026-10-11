# BLOCKERS

Problems an agent cannot or must not solve alone: needs sudo, the device, a user decision, or would
change a fixed tech decision. Add an entry, set the task to `BLOCKED` in PROGRESS.md, commit, stop.
Newest entry first. Keep entries short; no long logs.

## Template
```
## B-<nn> <short title>            Status: OPEN | RESOLVED
- Task: <task ID>
- Agent / date:
- Problem: <what fails, 1 to 3 lines; key error line only>
- Tried: <what was attempted and the result>
- Options: <A / B, with trade-offs>
- Needs from user: <exact decision or action>
- Resolution: <filled when resolved, with commit hash>
```

## Open

_none_

## Resolved

## B-01 Android Studio missing            Status: RESOLVED
- Task: P0-01
- Agent / date: AGY-1 / 2026-10-11
- Problem: Android Studio missing, so the required build JDK (JetBrains Runtime bundled with Android Studio) is unavailable; only OpenJDK 27 is installed.
- Tried: Searched /opt, home, JetBrains Toolbox, pacman, flatpak; no 'jbr' directory exists.
- Options: A) user installs Android Studio (e.g. tarball to ~/android-studio, no sudo needed, or AUR package); B) user relaxes the JDK rule.
- Needs from user: install Android Studio and tell the agent its path.
- Resolution: user installed Android Studio at /home/rava/.local/share/JetBrains/Toolbox/apps/android-studio (JBR 25.0.3 in jbr/) on 2026-10-11; verified and configured org.gradle.java.home in ~/.gradle/gradle.properties.

