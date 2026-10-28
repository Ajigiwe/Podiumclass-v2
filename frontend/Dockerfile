# Production image for the Podium web app.
#
# Two stages: build the SPA with Vite, then serve the static output with nginx.
# Vite's dev server is a development tool -- it compiles on request, keeps a
# websocket open and warns loudly against production use -- so it never runs
# behind a public hostname.
FROM node:22-alpine AS build

WORKDIR /app

# Copy manifests first: the dependency layer is reused until they change, which
# is what makes an ordinary source-only rebuild take seconds.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Vite inlines these at build time, so they are build arguments, not runtime
# environment. The API defaults to this same origin (nginx proxies /api), which
# is what removes CORS from the production path entirely.
ARG VITE_API_URL=""
ARG VITE_GOOGLE_CLIENT_ID=""
ARG VITE_GOOGLE_ALLOWED_ORIGINS=""
ARG VITE_POSTHOG_KEY=""
ARG VITE_POSTHOG_HOST=""
ENV VITE_API_URL=$VITE_API_URL \
    VITE_GOOGLE_CLIENT_ID=$VITE_GOOGLE_CLIENT_ID \
    VITE_GOOGLE_ALLOWED_ORIGINS=$VITE_GOOGLE_ALLOWED_ORIGINS \
    VITE_POSTHOG_KEY=$VITE_POSTHOG_KEY \
    VITE_POSTHOG_HOST=$VITE_POSTHOG_HOST

RUN npm run build


# Development target, used by the root docker-compose.yml: same base with the
# source and node modules, running Vite's dev server instead of a static build.
FROM build AS dev
EXPOSE 3000
CMD ["npm", "run", "dev", "--", "--host"]


FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q --spider http://127.0.0.1/healthz || exit 1
