// The Wedding Seal — one small server: the invitation, and the RSVPs that come back.
// node:http, no framework, in the spirit of the rest of this repo.
'use strict';

const http = require('node:http');
const fs   = require('node:fs');
const path = require('node:path');

const PORT        = process.env.PORT || 8080;
const RESEND_KEY  = process.env.RESEND_API_KEY || '';
const NOTIFY_TO   = process.env.NOTIFY_EMAIL   || '';
const NOTIFY_FROM = process.env.NOTIFY_FROM    || 'RSVP <rsvp@theweddingseal.com>';
const ADMIN_TOKEN = process.env.ADMIN_TOKEN    || '';

const PUBLIC = path.join(__dirname, 'public');
const STORE  = path.join('/tmp', 'rsvps.json');

// ---------------------------------------------------------------- storage
// The container disk is ephemeral, so this file backs the /admin view only.
// The email is the real record.
function readAll() {
  try { return JSON.parse(fs.readFileSync(STORE, 'utf8')); } catch { return []; }
}

function append(entry) {
  const all = readAll();
  all.push(entry);
  try {
    fs.writeFileSync(STORE, JSON.stringify(all, null, 2));
  } catch (e) {
    console.error('could not persist rsvp:', e.message);
  }
  return all.length;
}

// ---------------------------------------------------------------- helpers
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.svg' : 'image/svg+xml',
  '.png' : 'image/png',
  '.jpg' : 'image/jpeg',
  '.ico' : 'image/x-icon',
  '.css' : 'text/css; charset=utf-8',
  '.js'  : 'text/javascript; charset=utf-8',
  '.txt' : 'text/plain; charset=utf-8',
};

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function send(res, code, body, headers = {}) {
  res.writeHead(code, {
    'Content-Type': 'text/plain; charset=utf-8',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    ...headers,
  });
  res.end(body);
}

function json(res, code, obj) {
  send(res, code, JSON.stringify(obj), { 'Content-Type': 'application/json; charset=utf-8' });
}

function readBody(req, limit = 32 * 1024) {
  return new Promise((resolve, reject) => {
    let n = 0;
    const chunks = [];
    req.on('data', c => {
      n += c.length;
      if (n > limit) { reject(new Error('too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

// ---------------------------------------------------------------- the email home
async function notify(entry) {
  if (!RESEND_KEY || !NOTIFY_TO) {
    console.log('RSVP (no mailer configured):', JSON.stringify(entry));
    return;
  }

  const yes = entry.attending === 'Joyfully accepts';
  const line = (label, value) => value
    ? `<tr>
         <td style="padding:7px 16px 7px 0;color:#8a8578;font:11px/1.5 Helvetica,Arial,sans-serif;letter-spacing:.14em;text-transform:uppercase;vertical-align:top;white-space:nowrap">${esc(label)}</td>
         <td style="padding:7px 0;color:#2b2a26;font:17px/1.55 Georgia,'Times New Roman',serif">${esc(value)}</td>
       </tr>`
    : '';

  const html = `
  <div style="background:#f7f3e9;padding:30px 16px;font-family:Georgia,'Times New Roman',serif">
    <div style="max-width:520px;margin:0 auto;background:#fdfbf5;padding:34px 30px;border:1px solid #e6ddc8">
      <p style="margin:0 0 6px;font:10px/1 Helvetica,Arial,sans-serif;letter-spacing:.34em;text-transform:uppercase;color:#a8874e">The Wedding Seal</p>
      <h1 style="margin:0 0 22px;font:400 27px/1.25 Georgia,serif;color:#2b2a26">
        ${yes ? 'A guest has accepted' : 'A guest has declined'}
      </h1>
      <table cellpadding="0" cellspacing="0" border="0" style="width:100%;border-collapse:collapse">
        ${line('Name', entry.name)}
        ${line('Response', entry.attending)}
        ${line('Knows them as', entry.relation)}
        ${line('Party', entry.guests)}
        ${line('Dietary', entry.dietary)}
        ${line('Song', entry.song)}
        ${line('Message', entry.message)}
      </table>
      <p style="margin:26px 0 0;padding-top:16px;border-top:1px solid #e6ddc8;font:12px/1.6 Helvetica,Arial,sans-serif;color:#8a8578">
        Received ${esc(entry.at)}
      </p>
    </div>
  </div>`;

  const r = await fetch('https://api.resend.com/emails', {
    method: 'POST',
    headers: { Authorization: `Bearer ${RESEND_KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      from: NOTIFY_FROM,
      to: [NOTIFY_TO],
      subject: `RSVP — ${entry.name} ${yes ? 'accepts' : 'declines'}`,
      html,
    }),
  });

  if (!r.ok) console.error('resend notify failed', r.status, await r.text().catch(() => ''));
}

// ---------------------------------------------------------------- static
function serveStatic(req, res, urlPath) {
  const rel  = urlPath === '/' ? 'index.html' : urlPath.replace(/^\/+/, '');
  const file = path.resolve(PUBLIC, rel);

  // Never serve outside public/.
  if (file !== PUBLIC && !file.startsWith(PUBLIC + path.sep)) {
    return send(res, 403, 'Forbidden');
  }

  fs.readFile(file, (err, buf) => {
    if (err) {
      // Anything unknown falls back to the invitation rather than a 404 page.
      return fs.readFile(path.join(PUBLIC, 'index.html'), (e2, home) => {
        if (e2) return send(res, 404, 'Not found');
        send(res, 200, home, { 'Content-Type': TYPES['.html'] });
      });
    }
    const type = TYPES[path.extname(file).toLowerCase()] || 'application/octet-stream';
    send(res, 200, buf, {
      'Content-Type': type,
      'Cache-Control': rel === 'index.html' ? 'no-cache' : 'public, max-age=3600',
    });
  });
}

// ---------------------------------------------------------------- router
const server = http.createServer(async (req, res) => {
  let url;
  try { url = new URL(req.url, 'http://localhost'); }
  catch { return send(res, 400, 'Bad request'); }

  const p = url.pathname;

  if (p === '/healthz') return json(res, 200, { ok: true });

  if (p === '/api/rsvp') {
    if (req.method !== 'POST') return json(res, 405, { ok: false, error: 'method' });

    let body;
    try { body = JSON.parse(await readBody(req)); }
    catch { return json(res, 400, { ok: false, error: 'bad json' }); }

    const clean = v => String(v == null ? '' : v).trim().slice(0, 1000);
    const entry = {
      name:      clean(body.name),
      attending: clean(body.attending),
      relation:  clean(body.relation),
      guests:    clean(body.guests),
      dietary:   clean(body.dietary),
      song:      clean(body.song),
      message:   clean(body.message),
      at:        new Date().toISOString(),
    };
    if (!entry.name || !entry.attending) return json(res, 400, { ok: false, error: 'missing fields' });

    const count = append(entry);
    // The guest should never wait on, or see, a mail failure.
    notify(entry).catch(e => console.error('notify threw:', e.message));
    console.log('RSVP', count, entry.name, '|', entry.attending, '|', entry.relation);
    return json(res, 200, { ok: true });
  }

  if (p === '/admin') {
    if (!ADMIN_TOKEN || url.searchParams.get('token') !== ADMIN_TOKEN) {
      return send(res, 404, 'Not found');
    }
    const all = readAll();
    const rows = all.map(e => `
      <tr>
        <td>${esc(e.name)}</td><td>${esc(e.attending)}</td><td>${esc(e.relation)}</td>
        <td>${esc(e.guests)}</td><td>${esc(e.dietary)}</td><td>${esc(e.song)}</td>
        <td>${esc(e.message)}</td><td>${esc(e.at)}</td>
      </tr>`).join('');
    return send(res, 200, `<!doctype html><meta charset="utf-8"><title>RSVPs</title>
      <style>body{font:14px/1.5 system-ui,sans-serif;padding:28px;background:#faf8f2}
      table{border-collapse:collapse;width:100%}th,td{border:1px solid #ddd6c4;padding:8px;text-align:left;vertical-align:top}
      th{background:#f1ead8;font-size:11px;letter-spacing:.1em;text-transform:uppercase}</style>
      <h1>RSVPs (${all.length})</h1>
      <table><tr><th>Name<th>Response<th>Relation<th>Party<th>Dietary<th>Song<th>Message<th>At</tr>${rows}</table>`,
      { 'Content-Type': TYPES['.html'] });
  }

  if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, 405, 'Method not allowed');
  serveStatic(req, res, p);
});

server.listen(PORT, () => console.log(`The Wedding Seal listening on ${PORT}`));
