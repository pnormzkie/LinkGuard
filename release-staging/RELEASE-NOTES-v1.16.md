# LinkGuard v1.16

One more live threat source for sharper malware detection.

## What's new
- **New live malware feed (URLhaus by abuse.ch).** LinkGuard now also checks links
  against abuse.ch's URLhaus database of sites actively distributing malware. A link
  hosting live malware is flagged as dangerous, even if other sources haven't caught
  up yet.

## Notes
- This adds one more independent source on top of the existing checks (Google Safe
  Browsing, VirusTotal, and others). All checks still run in parallel and a single
  source being unavailable never blocks a scan.
- No change to how links are opened: LinkGuard never opens a link for you; it only
  checks where it goes.
