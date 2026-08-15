# LinkGuard v1.20

This maintenance release makes link scanning faster, more predictable, and safer during slow or unreliable network conditions.

## What's improved

- Faster bounded scanning through parallel static-page inspection and reputation-provider checks.
- External provider requests now stop promptly when their timeout expires or a scan is cancelled.
- Redirect resolution now follows a strict three-second total deadline.
- Page inspection is limited to a strict three-second total network budget.
- Centralized DNS anti-SSRF validation blocks private, loopback, link-local, mixed public/private, and unsafe destinations before connection.
- Redirect and page-inspection requests share the same DNS safety policy.

Detection rules, scoring thresholds, provider mappings, trusted-domain lists, UI, database behavior, and API configuration are unchanged.
