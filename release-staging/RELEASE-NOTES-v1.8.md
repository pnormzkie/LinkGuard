## LinkGuard v1.8 — NextDNS detection fix

### Fixed
- The "Blocked by NextDNS" check now actually works. The previous endpoint silently ignored LinkGuard's DNS filter profile, so DNS-level threat and tracker blocks were never detected. LinkGuard now queries the filtered DNS-over-HTTPS endpoint directly and recognizes blocks reliably.
- Domains that simply don't exist are no longer mislabeled as "Blocked by NextDNS".

**SHA-256 (LinkGuard-v1.8.apk):** `E354D2697065B8033018EE20ABEAF8CDFFC4C735E153E3B98553E444436A037D`
