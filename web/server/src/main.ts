import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';
import * as indexManager from './index-manager.js';
import { search } from './search.js';
import { handleStream, resolveStation, parsePlaylistEntries } from './proxy.js';
import { handleArtwork } from './artwork.js';
import { subscribe, forgetChannel } from './events.js';
import { reportClick } from './radiobrowser.js';
import { gateEnabled, isAuthed, checkCode, grantCookie } from './auth.js';

/**
 * The whole server: a stream proxy, a search API over the station index, and the static client.
 *
 * One process, one container, `node:http` and one native dependency. A hobby radio station for a
 * handful of people does not need a framework, and every dependency here is one more thing that can
 * stop installing in three years' time.
 */

const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.webmanifest': 'application/manifest+json',
  '.woff2': 'font/woff2',
  '.map': 'application/json',
};

function json(res: http.ServerResponse, status: number, payload: unknown): void {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

async function readBody(req: http.IncomingMessage, limit = 1024 * 1024): Promise<string> {
  const chunks: Buffer[] = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > limit) throw new Error('body too large');
    chunks.push(chunk as Buffer);
  }
  return Buffer.concat(chunks).toString('utf8');
}

/** True when the request reached us over TLS, so the access cookie can be marked Secure. */
function isSecure(req: http.IncomingMessage): boolean {
  const proto = String(req.headers['x-forwarded-proto'] ?? '').split(',')[0].trim();
  return proto === 'https' || (req.socket as { encrypted?: boolean }).encrypted === true;
}

function serveStatic(req: http.IncomingMessage, res: http.ServerResponse, pathname: string): void {
  const root = path.resolve(config.clientDir);
  const requested = pathname === '/' ? '/index.html' : pathname;
  let file = path.resolve(root, '.' + requested);

  // Anything that escapes the client directory, or any unknown route, is the app shell: this is a
  // single-page app and a deep link has to land somewhere.
  if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    file = path.join(root, 'index.html');
  }
  if (!fs.existsSync(file)) {
    res.writeHead(503, { 'Content-Type': 'text/plain' }).end('client not built');
    return;
  }

  const extension = path.extname(file).toLowerCase();
  const immutable = file.includes(`${path.sep}assets${path.sep}`);
  res.writeHead(200, {
    'Content-Type': MIME[extension] ?? 'application/octet-stream',
    'Cache-Control': immutable ? 'public, max-age=31536000, immutable' : 'no-cache',
  });
  fs.createReadStream(file).pipe(res);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
  const pathname = url.pathname;
  const query = url.searchParams;

  try {
    // ---------------------------------------------------------------- open endpoints

    if (pathname === '/api/config') {
      json(res, 200, {
        needsCode: gateEnabled(),
        authed: isAuthed(req),
        maxChannels: config.maxChannels,
      });
      return;
    }

    if (pathname === '/api/gate' && req.method === 'POST') {
      const body = JSON.parse((await readBody(req)) || '{}') as { code?: string };
      if (!checkCode(String(body.code ?? ''))) {
        // A deliberate pause: this is one shared password, so slow guessing down a little.
        await new Promise((r) => setTimeout(r, 600));
        json(res, 403, { ok: false });
        return;
      }
      grantCookie(res, isSecure(req));
      json(res, 200, { ok: true });
      return;
    }

    if (pathname === '/healthz') {
      json(res, 200, { ok: true, index: indexManager.status().total });
      return;
    }

    // ---------------------------------------------------------------- the gate

    if (pathname.startsWith('/api/') && !isAuthed(req)) {
      json(res, 401, { error: 'access code required' });
      return;
    }

    // ---------------------------------------------------------------- audio

    if (pathname === '/api/stream') {
      const target = query.get('u');
      if (!target) {
        res.writeHead(400).end('missing u');
        return;
      }
      await handleStream(
        { url: target, sid: query.get('sid') ?? undefined, channel: query.get('ch') ?? undefined },
        res,
      );
      return;
    }

    if (pathname === '/api/events') {
      const sid = query.get('sid');
      if (!sid) {
        res.writeHead(400).end('missing sid');
        return;
      }
      subscribe(sid, res);
      return;
    }

    if (pathname === '/api/forget') {
      const sid = query.get('sid');
      const channel = query.get('ch');
      if (sid && channel) forgetChannel(sid, channel);
      json(res, 200, { ok: true });
      return;
    }

    if (pathname === '/api/art') {
      const target = query.get('u');
      if (!target) {
        res.writeHead(400).end('missing u');
        return;
      }
      await handleArtwork(target, res);
      return;
    }

    // ---------------------------------------------------------------- discovery

    if (pathname === '/api/search') {
      const index = indexManager.current();
      if (!index) {
        json(res, 200, { hits: [], expansions: [], indexReady: false });
        return;
      }
      const results = search(
        index,
        query.get('q') ?? '',
        {
          httpsOnly: query.get('https') === '1',
          includeUnreachable: query.get('unreachable') === '1',
          country: query.get('country') ?? undefined,
          codec: query.get('codec') ?? undefined,
          minBitrate: Number(query.get('minBitrate') ?? 0) || undefined,
        },
        Math.min(Number(query.get('limit') ?? 80) || 80, 200),
        (query.get('drop') ?? '').split(',').filter(Boolean),
      );
      json(res, 200, { ...results, indexReady: true });
      return;
    }

    if (pathname === '/api/browse') {
      const index = indexManager.current();
      if (!index) {
        json(res, 200, { tags: [], trending: [], countries: [] });
        return;
      }
      const tag = query.get('tag');
      if (tag) {
        json(res, 200, { hits: index.byTag(tag, 80) });
        return;
      }
      json(res, 200, {
        tags: index.popularTags(28),
        trending: index.trending(24),
        countries: index.countries(40),
      });
      return;
    }

    if (pathname === '/api/index-status') {
      json(res, 200, indexManager.status());
      return;
    }

    if (pathname === '/api/index-refresh' && req.method === 'POST') {
      void indexManager.refresh(true);
      json(res, 202, { started: true });
      return;
    }

    if (pathname === '/api/resolve') {
      const target = query.get('u');
      if (!target) {
        res.writeHead(400).end('missing u');
        return;
      }
      try {
        const resolved = await resolveStation(target);
        if (!resolved) {
          json(res, 200, { ok: false, reason: 'nothing playable at that URL' });
          return;
        }
        // Fill in whatever the index already knows about this stream, so a station added by URL
        // reads the same as one added from search.
        const known = indexManager.current()?.findByUrl(resolved.url) ?? null;
        json(res, 200, { ok: true, ...resolved, known });
      } catch (error) {
        json(res, 200, { ok: false, reason: (error as Error).message });
      }
      return;
    }

    if (pathname === '/api/parse-playlist' && req.method === 'POST') {
      json(res, 200, { entries: parsePlaylistEntries(await readBody(req, 4 * 1024 * 1024)) });
      return;
    }

    if (pathname.startsWith('/api/click/') && req.method === 'POST') {
      void reportClick(pathname.slice('/api/click/'.length));
      json(res, 202, { ok: true });
      return;
    }

    if (pathname.startsWith('/api/')) {
      json(res, 404, { error: 'no such endpoint' });
      return;
    }

    // ---------------------------------------------------------------- the client

    serveStatic(req, res, pathname);
  } catch (error) {
    console.error(`[http] ${pathname}:`, error);
    if (!res.headersSent) json(res, 500, { error: (error as Error).message });
    else res.destroy();
  }
});

// Streams stay open for hours. The default two-minute idle timeout would cut every one of them.
server.requestTimeout = 0;
server.headersTimeout = 60_000;
server.timeout = 0;
server.keepAliveTimeout = 72_000;

indexManager.start();

server.listen(config.port, config.host, () => {
  console.log(`Tasty Radio on http://${config.host}:${config.port}`);
  console.log(`  access code   ${gateEnabled() ? 'required' : 'NOT SET — the site is open'}`);
  console.log(`  client        ${config.clientDir}`);
  console.log(`  data          ${config.dataDir}`);
});

for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.on(signal, () => {
    console.log(`\n${signal} — closing`);
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 3000).unref();
  });
}
