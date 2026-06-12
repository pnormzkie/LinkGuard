# Coverage Matrix

> Adapt columns to match your actual stack in Project Context.

| Task Type      | Frontend                             | Backend                              | API / Integration            |
|----------------|--------------------------------------|--------------------------------------|------------------------------|
| Bug fix        | Regression test for the broken case  | Unit test for root cause             | Contract test if API touched |
| New feature    | Component test + happy path E2E      | Unit + integration test              | E2E + schema validation      |
| Refactor       | Existing tests must stay green       | Existing tests must stay green       | No new surface = no new tests required |
| Config change  | Smoke test in affected env           | Smoke test in affected env           | Health check / ping          |

**Exception rule:** If a test type is skipped, document why in `tasks/todo.md` before marking done.

## Pattern Escalation Threshold

| Occurrences | Action |
|-------------|--------|
| 1st time    | Add entry to `tasks/lessons.md`, status: active |
| 2nd time    | Append "Recurred on [DATE]" to existing entry |
| 3rd time    | Promote rule to Core Principles in SKILL.md |
