# LinkGuard v1.22

This maintenance release improves launcher consistency and makes resolved-link opening safer.

## What's fixed

- Uses the approved blue LinkGuard icon consistently on Samsung and other Android launcher/search surfaces.
- Opens the exact verified destination after a redirect scan instead of requesting the original mutable redirect link again.
- Keeps the original link as the opening target when redirect resolution is incomplete, timed out, looped, or blocked.

Scanning rules, risk scoring, provider behavior, network-call count, UI layout, and saved scan history are unchanged.
