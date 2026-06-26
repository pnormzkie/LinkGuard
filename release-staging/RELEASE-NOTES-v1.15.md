# LinkGuard v1.15

Smarter, more accurate link checks.

## What's new
- **Follows shortened & redirect links** to their real destination before you open
  them — a safe-looking short link can no longer hide a dangerous page. The checks now
  run on where the link *actually* goes.
- The block screen shows the real destination ("Goes to ...") when a link redirects.

## Fixes
- **Fewer false alarms** on legitimate sites — major sign-in pages (e.g. Battle.net)
  are no longer wrongly flagged as dangerous.
- **More accurate brand-spoofing detection** — real look-alike domains are still caught,
  while ordinary words that merely contain a brand name no longer trigger a warning.

## Notes
- Redirect following is read-only and bounded (max hops + short timeout); LinkGuard never
  opens a link for you, and only follows secure (HTTPS) hops.
