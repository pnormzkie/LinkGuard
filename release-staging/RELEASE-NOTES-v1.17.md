# LinkGuard v1.17

Catches fake login pages, not just bad addresses.

## What's new
- **Login-form check on suspicious links.** When a link already looks suspicious and leads
  to an unrecognized site, LinkGuard now checks whether the page is asking for your
  **password** — and warns you ("Login form on an unverified site"). This catches fresh
  phishing pages that have a clean-looking address but exist only to steal your sign-in.
- It also flags when a login page tries to **send your password to a different site**.

## Notes
- This check is read-only and runs only for links that are already suspicious and not from a
  recognized, trusted site — so it adds no delay to normal, safe links. LinkGuard never opens
  a link for you; it only checks where it goes and what it asks for.
- It inspects the page's static content; pages that build their form entirely with scripts may
  not be detected.
