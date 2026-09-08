# LinkGuard v1.28

## False-positive fixes

- Stop treating Android Download Manager completion notifications as received-message links.
- Prevent downloaded filenames such as `ASTRA-FABLE-CLAUDE-v2.2.zip` from becoming synthetic HTTPS alerts.
- Recognize Indeed's official `secure.indeed.com` OAuth host without broadly trusting every Indeed subdomain.
- Suppress the generic long-URL heuristic for curated official hosts while preserving redirect and external reputation checks.

## Verification

- Add regression coverage for both Android Download Manager package variants.
- Add regression coverage for long, encoded Indeed OAuth authorization URLs and credential-form inspection eligibility.
- Pass all 358 JVM tests, release lint, R8 shrinking, signed APK verification, and an in-place emulator deployment.
