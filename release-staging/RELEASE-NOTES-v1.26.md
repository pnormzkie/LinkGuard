# LinkGuard v1.26

## Security and reliability

- Refresh an already-visible threat alert when a newer dangerous-link report arrives.
- Close malformed threat alerts that do not contain a valid scan result.
- Restrict update-download completion handling to the protected Android system broadcast.
- Keep Android component exposure covered by an automated security contract test.

## Test and release hardening

- Add device tests for threat-alert lifecycle behavior and Room database operations.
- Verify the v1-to-v2 scan-history migration preserves existing records.
- Remove redundant release shrinker rules while retaining current Gson history compatibility.

This release contains no changes to scan scoring, security-provider logic, or the saved-history schema.
