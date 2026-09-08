# The Wedding Seal

A wedding invitation, from a stationery house that does not exist, for a wedding that is not
happening, between Thomas and the mother of his friend Matt.

Live at **https://theweddingseal.com**.

## What's here

| Path | What it is |
|---|---|
| `email/invite.html` | The invitation email. Tables and inline styles only. |
| `public/index.html` | The landing page: the envelope that opens, the invitation, the RSVP form. |
| `server.js` | Serves the page, takes the RSVP, emails it to Thomas via Resend. |

## Why the envelope is on the web and not in the email

Gmail strips `<style>` blocks unreliably, removes all JavaScript, and ignores CSS animation.
An envelope that opens cannot run inside the message. So the email is a static invitation
card with an **Open your invitation** button, and the animation lives on the landing page —
which is exactly what Paperless Post and Greenvelope do, for exactly this reason.

## Deliverability notes

The domain is new, so it has no sending reputation. What we do about that:

- SPF, DKIM and DMARC are all set before the first send.
- Resend's click and open tracking are **off**. Both rewrite links through a tracking
  subdomain, which reads as bulk mail.
- The From address is a person, not `noreply@`.
- Test sends go to the sender's own Gmail first, to confirm inbox placement.

## Configuration

| Variable | Purpose |
|---|---|
| `RESEND_API_KEY` | Sends the RSVP notification. Secret. |
| `NOTIFY_EMAIL` | Where RSVPs land. |
| `NOTIFY_FROM` | From address on the notification. |
| `ADMIN_TOKEN` | Guards `/admin`, which lists every response. Secret. |

`/admin?token=…` reads a file on the container's ephemeral disk, so it empties on redeploy.
The notification email is the durable record.
