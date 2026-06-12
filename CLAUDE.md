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

## Skills

- **`/create-expert [expertise]`** — Analyzes this repo and generates scoped, invocable
  Claude Code subagents in `.claude/agents/*.md`. No arg → proposes a tailored roster for
  approval; with an arg (e.g. `/create-expert networking`) → builds that single expert.
  Each agent is grounded in real LinkGuard paths/classes and scoped to role-appropriate
  tools/model. Re-run after large refactors. Defined in
  `.claude/skills/create-expert/SKILL.md`.
