## LinkGuard v1.7 — Security hardening release

### Security
- App updates are now verified before install: the download must come from GitHub over HTTPS, and the APK's signing certificate must match the installed app — otherwise the update is rejected.
- Fixed an evasion bug where specially crafted URLs could silently knock out the Google Safe Browsing check.
- Scanner services (Safe Browsing, VirusTotal, Hybrid Analysis, NextDNS) no longer treat outages or errors as "safe" — when a check can't run, LinkGuard now tells you the verdict is unverified instead of green-lighting the link.
- Fixed domain parsing so URLs with embedded credentials (user@host tricks) can no longer confuse the trusted-domain check.
- Removed unused components and an unnecessary startup permission to reduce attack surface.
- Scanned URLs are no longer written to device logs in release builds.

### Improvements
- You can now cancel a link scan in progress instead of waiting for it to finish.
- Scan results refresh after 15 minutes instead of being remembered until the app restarts.
- Consistent trusted-domain list across all scanners.

**SHA-256 (LinkGuard-v1.7.apk):** `A90D9709A52A8BF0F4F059F523942B2E28357FC67A53ECE9E73696D7A676A03C`
