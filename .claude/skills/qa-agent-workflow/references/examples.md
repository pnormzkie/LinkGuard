# Examples

> Replace placeholders with your actual stack and task details.

## Good Plan Entry (`tasks/todo.md`)

```markdown
## Task: [SHORT TITLE]
**Goal:** [One sentence — what does done look like?]
**Rollback:** [How to undo — e.g. revert auth/callback.ts to previous commit]

- [ ] Reproduce the issue / confirm the requirement
- [ ] Identify root cause / design approach
- [ ] Implement fix or feature
- [ ] Verify it works — run tests, check logs
- [ ] Add regression test or new test coverage
- [ ] Document exception if any coverage skipped

## Review
- Result: [what actually happened]
- Tests added: [list them]
- Lessons captured: yes / no — see lessons.md
```

## Good Bug Report

```
Bug: [Short description]
Repro:
  1. [Step 1]
  2. [Step 2]
  3. Expected: [what should happen]
  4. Actual: [what actually happens]

Root cause: [explain the actual cause, not the symptom]
Fix: [what you changed and where]
Verified: [how you confirmed it's fixed + any regression tests added]
```

## Good Lesson Entry (`tasks/lessons.md`)

```
## [DATE] — [Short title describing the mistake]
**Mistake:** [What went wrong]
**Context:** [When/where it happened — feature, file, environment]
**Rule:** [The hard rule that prevents this recurring]
**Status:** active | superseded by [DATE — TITLE]
```

Entries are never deleted — only marked superseded. Review `active` entries at session start.
