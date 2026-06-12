---
name: create-expert
description: Use when someone asks to create an expert agent, generate a specialized subagent for this repo, build a domain expert, scaffold a Claude Code agent, or "make an agent that knows this codebase". Analyzes the repo and writes scoped agent files to .claude/agents/.
argument-hint: [expertise e.g. networking | qa | release-signing] (optional)
disable-model-invocation: true
---

## What This Skill Does

Generates **real, invocable Claude Code subagent files** (`.claude/agents/*.md`) whose
system prompts are grounded in *this* repository. It inspects the actual code (manifest,
build config, source packages, tests) and produces experts tailored to the domains that
exist here — each scoped to the right tools and model for its role.

- **No argument** → analyze the repo, propose a tailored roster, write the approved agents.
- **With an argument** (e.g. `/create-expert networking`) → build that single expert.

Output target: `.claude/agents/<name>-expert.md`. Each file is immediately usable via the
Agent tool / `@agent-name`.

---

## Step-by-Step Workflow

### Step 1 — Analyze the repo (always run this first)

Read enough to ground the experts in reality. Do NOT invent domains the repo doesn't have.

1. Read `CLAUDE.md` (root) and any `<repo>/CLAUDE.md` for stack, conventions, landmines.
2. Read `tasks/lessons.md` and `tasks/todo.md` if present — active lessons become agent rules.
3. Inspect build/config to learn the stack and dependencies. For this repo that means:
   - `app/build.gradle` (deps: which providers, networking, DB, camera, ML libs)
   - `app/src/main/AndroidManifest.xml` (permissions, components, services, receivers)
   - `settings.gradle`, `gradle.properties`, `local.properties` (keys, signing — never echo secret values)
4. Map the source packages: `find app/src -type f -name '*.kt'` and group by package
   (`data/provider`, `domain/scanner`, `domain/scoring`, `scanner`, `service`, `ui`,
   `update`, `util`) plus `app/src/test`.
5. Note the build/verify commands (see the LinkGuard build memory: cached Gradle 8.9 +
   Adoptium JDK 17, unsandboxed; keys in `local.properties`).

From this, derive **candidate expertise domains** — one per cohesive responsibility area
that actually exists in the code. See "Candidate roster for this repo" below for the
domains LinkGuard currently exposes; re-derive rather than copy if the code has changed.

### Step 2 — Decide scope

- **If `Create a skill that can create different expertise agent based on this repo.` (an argument) was provided:** map it to the closest real domain from Step 1.
  If it doesn't match anything in the repo, say so and ask before inventing one. Skip to Step 4.
- **If no argument:** continue to Step 3.

### Step 3 — Propose the roster (no-argument path)

Present the derived candidate experts to the user with `AskUserQuestion`
(`multiSelect: true`), each option labeled with the expert name and a one-line scope.
Only list domains backed by code you actually found in Step 1. Let the user pick which to
create. Do not write anything until they choose.

### Step 4 — Write each approved agent

For every selected/requested expert, create `.claude/agents/<name>-expert.md` using the
**Agent File Template** below. Before writing:

- **Check for an existing file.** If `.claude/agents/<name>-expert.md` already exists,
  show the user the current frontmatter and ask whether to overwrite, skip, or merge.
  Never silently clobber an agent the user may have hand-tuned.
- Fill the system prompt with **repo-specific** facts: real package paths, real class
  names, real dependencies, the relevant landmines, and the verify command. Generic
  "you are an Android expert" prompts are a failure — cite this codebase.
- Scope `tools` and `model` per the **Scoping Reference**.

### Step 5 — Report

List the files written (path + one-line purpose), how to invoke them, and any domain the
user declined or that had no code backing. Suggest re-running after large refactors so the
roster stays accurate.

---

## Agent File Template

Write each agent in exactly this shape. Omit optional frontmatter you don't need.

```markdown
---
name: <kebab-case-name>-expert        # must equal the filename without .md
description: Use this agent when <concrete triggers tied to this repo's domain>. <One line on what it's good at>.
tools: <comma-separated subset>        # omit entirely to inherit all tools
model: opus | sonnet | haiku | inherit # omit to inherit the session model
---

You are the **<Domain> expert** for the LinkGuard Android app (`com.linkguard.app`,
Kotlin, minSdk 26 / targetSdk 34, Gradle 8.9 + JDK 17).

## Your domain
<2-4 sentences naming the EXACT files/packages this agent owns, e.g.
"`app/src/main/java/com/linkguard/app/data/provider/*` — the VirusTotal, Safe Browsing,
Hybrid Analysis, and NextDNS signal providers, plus the OkHttp client wiring.">

## What you know
- Key types: <real class names from the repo>
- Dependencies: <real libs from build.gradle relevant to this domain>
- Landmines: <relevant fragile areas, e.g. exported components, API key handling, secrets>

## How you work
- Follow the repo's CLAUDE.md: root-cause fixes, surgical changes, evidence before signoff.
- Cite real file paths and line numbers; never invent APIs, files, or test results.
- Match existing Kotlin style and error-handling patterns in the touched files.
- Verify with: <the relevant build/test command for this domain>.
- State residual risk and what you did NOT cover.

## Out of scope
- <domains owned by other experts — hand off rather than guess>
```

---

## Scoping Reference

Pick `tools` and `model` by the expert's job. Read-only/review roles must NOT get write
or shell access; only build/release/test roles get `Bash`.

| Expert archetype            | tools                                  | model   | Why |
|-----------------------------|----------------------------------------|---------|-----|
| Review / audit / security   | `Read, Grep, Glob`                     | `opus`  | Hard reasoning, must not mutate code |
| Code / feature implementer  | `Read, Edit, Write, Grep, Glob`        | inherit | Edits source, no shell needed |
| Test / QA                   | `Read, Edit, Grep, Glob, Bash`         | inherit | Writes + runs tests |
| Build / release / signing   | `Read, Edit, Grep, Glob, Bash`         | inherit | Runs Gradle, handles keystore |
| Networking / providers      | `Read, Edit, Grep, Glob`               | inherit | Edits providers; no shell |
| Lookup / explainer          | `Read, Grep, Glob`                     | `haiku` | Cheap, fast, read-only |

Notes:
- Omit the `tools:` line entirely to inherit ALL tools — only do this when the role
  genuinely needs broad access. Prefer the narrowest set that works.
- `model: opus` for deep analysis/security; `haiku` for cheap read-only lookups;
  omit (inherit) otherwise.

---

## Candidate Roster for This Repo

Domains currently present in LinkGuard (re-derive from Step 1 if the code has changed —
do not assume these still match). Use these as proposal options; their suggested scoping
is in parentheses.

- **android-security-expert** — exported components, permissions (`SYSTEM_ALERT_WINDOW`,
  `BIND_NOTIFICATION_LISTENER_SERVICE`, `REQUEST_INSTALL_PACKAGES`), intent handling in
  `LinkInterceptActivity`, secret/API-key handling. (read-only, opus)
- **threat-scanning-expert** — `domain/scanner`, `domain/scoring`, `domain/orchestrator`,
  `scanner/` (HeuristicEngine, ScoringEngine, ScanOrchestrator, UrlScanner, QR validators).
  (implementer)
- **reputation-providers-expert** — `data/provider/*` (VirusTotal, Safe Browsing, Hybrid
  Analysis, NextDNS), OkHttp/Gson wiring, `ScanRepository`. (implementer, no Bash)
- **qr-camera-expert** — `ui/QrScanner*`, CameraX + ML Kit barcode scanning,
  `QrTypeDetector`, `PaymentQrValidator`. (implementer)
- **android-ui-expert** — `ui/` activities, ViewModel/LiveData, ViewBinding, layouts/res.
  (implementer)
- **service-notification-expert** — `service/LinkNotificationService`, receivers, boot
  restart, foreground service, threat-alert popup flow. (implementer)
- **qa-test-expert** — `app/src/test/**`, JUnit + coroutines-test; writes/runs unit tests,
  regression coverage per CLAUDE.md. (test/QA, Bash)
- **release-signing-expert** — `build.gradle` signing config, `local.properties` keystore,
  ProGuard, `update/UpdateChecker`/`UpdateInstaller`, version bumps. (build/release, Bash)

---

## Notes & Guardrails

- **Only create experts the repo backs.** If a requested expertise has no corresponding
  code, say so and ask — do not fabricate a domain.
- **Never overwrite an existing agent silently** (Step 4).
- **Never print secret values** from `local.properties` (keystore passwords, API keys).
  Reference the key *names* only.
- Keep each generated system prompt grounded in real paths/classes from this repo. A
  prompt that would work for "any Android app" is wrong — cite LinkGuard specifics.
- This skill writes files (side effects) → it is not model-auto-invoked
  (`disable-model-invocation: true`); trigger it explicitly.
- Generated agents inherit the repo's `CLAUDE.md` rules; don't restate the whole file in
  each prompt — reference it.
