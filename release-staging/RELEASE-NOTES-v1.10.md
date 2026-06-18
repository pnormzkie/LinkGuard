## LinkGuard v1.10 — Reliable alerts, stronger detection, quota safety

### Fixed
- **Threat alerts now reliably reach you on Android 13 and 14.** LinkGuard now asks for notification permission when you turn on protection, and guides you to allow full-screen alerts on Android 14 — previously a dangerous-link warning could be silently suppressed if those weren't granted.

### Added — detection
- **Look-alike (homoglyph) domain detection.** Catches addresses that swap in Cyrillic/Greek letters or use punycode (`xn--`) to impersonate real brands — bypasses that the old number-swap check missed.
- **More scam-message patterns.** Added courier "release/processing fee" scams, work-from-home/recruitment lures, and tech-support scares, in English and Taglish.
- **Newly-registered-domain check.** Flags brand-new domains (a common phishing signal) via a public domain-registry (RDAP) lookup.

### Improved — reliability
- **Daily scan budget** that survives app restarts, keeping automatic notification scanning within the threat-intelligence providers' free-tier quotas.
- **One automatic retry** on transient network/server errors — without retrying rate-limit (429) responses, so quota isn't wasted.

**SHA-256 (LinkGuard-v1.10.apk):** `3039F844775D4397BA39CC0AAC3FEC068AD9FF9871135E9AA6B2A0CABEF6E13C`
