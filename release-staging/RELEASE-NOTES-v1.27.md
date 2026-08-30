# LinkGuard v1.27

## Detection hardening

- Expand notification URL extraction for Unicode and uncommon domains while preserving balanced punctuation.
- Keep local threat heuristics active when network-provider quotas or rate limits are reached.
- Report partial provider coverage clearly and avoid caching incomplete clean verdicts.
- Verify redirects more defensively, including scoped redirect parameters and static cross-domain page redirects.
- Prefer the newest timestamped Hybrid Analysis report while retaining conservative handling for incomplete data.
- Distinguish historical offline URLhaus records from currently active malware infrastructure.

## Payment QR safety

- Validate the exact payment QR format and require the CRC to be the final field.
- Treat structurally valid payment QR codes as payee-unverified rather than safe.
- Show a clear reminder to verify merchant and recipient details before paying.

## Verification

- Add focused regression coverage for URL extraction, redirect handling, provider freshness, partial coverage, and payment QR behavior.
- Pass 361 JVM tests and 6 Android device tests, including notification scanning, link interception, and the payment QR result flow.
- Pass debug and signed release builds, R8 shrinking, and release lint checks.
