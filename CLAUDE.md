# Project Memory

> Fill in this file once per project. Claude reads it at the start of every session.
> The qa-agent-workflow skill applies all its rules through this context.

## Project Context

```
Project name:
Stack:
Repo structure:
Test framework(s):
CI/CD:
Conventions:
Known landmines:
Rollback method:
```

## Active Lessons

> Claude: review tasks/lessons.md at session start. Paste any active lessons here
> so they're loaded immediately without reading the file.

<!-- Copy active lessons here for fast access -->

## Standing Rules

> Add any project-specific overrides to qa-agent-workflow here.
> Example: "Always use pnpm, never npm."

<!-- Add project-specific rules below -->

- **Commit the release notes.** `release-staging/release-notes-v<X>.md` is tracked from v1.33
  onward, so what was announced to users lives in history alongside the commits it describes.
  v1.30–v1.32 predate this and stay untracked; do not backfill them. The APKs and the
  `publish-v<X>.sh` scripts keep their existing treatment.

- **The in-app update dialog renders the published release body above its buttons, inside a
  scroll view.** Keep the body short — roughly 9 lines / 550 characters fits a 1080x2400
  screen with "Update now" visible without scrolling. v1.33 originally shipped ~3,600
  characters and buried the button under a wall of text. The body is editable through the
  GitHub API after publishing, so this is fixable without a new build — but verify it on a
  device rather than assuming, because "present in the UI dump" is not "visible on screen".

## Skills

- **`/create-expert [expertise]`** — Analyzes this repo and generates scoped, invocable
  Claude Code subagents in `.claude/agents/*.md`. No arg → proposes a tailored roster for
  approval; with an arg (e.g. `/create-expert networking`) → builds that single expert.
  Each agent is grounded in real LinkGuard paths/classes and scoped to role-appropriate
  tools/model. Re-run after large refactors. Defined in
  `.claude/skills/create-expert/SKILL.md`.
