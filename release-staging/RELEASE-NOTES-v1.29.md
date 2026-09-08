# LinkGuard v1.29

## False-positive fixes

- Reject downloaded filenames and email addresses as manual-scan URLs instead of inventing an HTTPS host.
- Keep real download links scannable while checking dangerous file extensions only in URL paths and query values.
- Treat mainstream domains such as `.io`, `.info`, `.shop`, and `.store` normally.
- Cap repeated phishing/marketing keywords as one weak evidence family.
- Treat ordinary login forms, tracker matches, and DNS policy blocks as supporting evidence unless corroborated.
- Require the scanned path and query to match an active URLhaus record before assigning a critical verdict; activity elsewhere on a shared host stays weak context.
- Prevent tracker labels from masking confirmed malicious-reputation findings.

## Verification

- Added regression coverage for URL normalization, trusted APK downloads, keyword families, DNS policy/tracker signals, credential forms, URLhaus shared-host paths, and reason precedence.
- Verified the signed app flow on an Android emulator and ran the full JVM test and release-build checks.
