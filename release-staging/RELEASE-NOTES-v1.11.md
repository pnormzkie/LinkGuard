# LinkGuard v1.11 (versionCode 12)

A redesign of the link-block experience, clearer safety decisions, and easier-to-read risk flags.

## What's new

**Redesigned block screen**
- The link-tap warning now matches the Scan Report look: status badge, risk ring, the tapped
  link, and the flags — with colors that follow the verdict (yellow = suspicious, red = dangerous).
- Centered, scrollable card with a dimmed background; the action buttons are always reachable.

**Safer, clearer decisions**
- Safe links no longer open silently. You now see a green "Safe link" card with **Open Link** /
  **Close**, so an accidental tap can be backed out of.
- For **dangerous** links there's no co-equal "Open Anyway" button anymore — only **Don't Open**
  is prominent. The override is a small, confirm-gated link ("I understand the risk"), so it can't
  be triggered by reflex.

**Grouped, collapsible risk flags**
- Flags are now grouped by category — **Heuristic**, **Vendors flagged**, **Domain checks** —
  each with a count. Tap a group to expand its details. Cleaner and easier to scan.
- Applies to the block screen and the Scan Report.

## Notes
- Detection, scoring, and verdicts are unchanged — this release is about clarity and control.
- Signed with the same release key as prior versions; existing installs can update in place.

SHA-256 (LinkGuard-v1.11.apk): 4daa63fda86a0f5eb2ce25c033a70f8a61fe546b8e373f16c60b9b14cd46eef0
