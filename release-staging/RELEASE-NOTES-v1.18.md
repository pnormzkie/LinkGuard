# LinkGuard v1.18

Stronger app-only protection against modern phishing and scam pages.

## What's new

- Structured, non-executing HTML analysis for password, OTP, payment, identity, recovery, and multi-step login forms.
- Detection for brand impersonation, ClickFix command scams, fake full-screen login overlays, encoded SVG/data payloads, obfuscated scripts, suspicious iframes, and urgent executable downloads.
- Trusted-link redirect hardening to catch open-redirect destinations without broadly scanning normal trusted pages.
- Better score calibration: generic URL words are weak evidence, and exact duplicate findings cannot inflate risk.
- More Philippine scam coverage for remote-access, task/commission, guaranteed-return investment, and digital-arrest payment messages.
- Domain-only QR links are now normalized and scanned; unsafe non-web QR schemes remain blocked.
- False-positive fix for the official ChatGPT Advanced Account Security link.

All page inspection remains app-only and bounded. LinkGuard does not execute page JavaScript, submit forms, or store scanned page content.
