# Tasty Radio, on the web. One image: the built client is served as static files by the same
# process that proxies the streams, so there is one container to run and one thing to restart.

FROM node:20-bookworm-slim AS build
WORKDIR /build

# Dependencies first, so a change to the source doesn't re-download the world.
COPY server/package.json server/package-lock.json server/
RUN cd server && npm ci
COPY client/package.json client/package-lock.json client/
RUN cd client && npm ci

COPY server server
COPY client client

# The client builds straight into server/public — see client/vite.config.ts.
RUN cd client && npm run build
RUN cd server && npm run build

# Drop the dev dependencies rather than reinstalling: better-sqlite3's native binding was compiled
# against this exact image, and a second install is a second chance to get a different one.
RUN cd server && npm prune --omit=dev


FROM node:20-bookworm-slim
ENV NODE_ENV=production
WORKDIR /app

COPY --from=build /build/server/node_modules ./node_modules
COPY --from=build /build/server/dist ./dist
COPY --from=build /build/server/public ./public
COPY --from=build /build/server/assets ./assets
COPY --from=build /build/server/package.json ./

# The station index lives here. A named volume mounted over it inherits this ownership, which is
# what lets the process run as an unprivileged user and still write its own index.
RUN mkdir -p /data && chown -R node:node /data
ENV DATA_DIR=/data
ENV PORT=8080

USER node
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
  CMD node -e "fetch('http://127.0.0.1:8080/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "dist/main.js"]
