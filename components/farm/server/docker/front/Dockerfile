FROM node:slim AS build

ENV PNPM_HOME=/pnpm \
    PATH=/pnpm:$PATH \
    NODE_OPTIONS=--max-old-space-size=1536

WORKDIR /app
RUN npm install --global npm pnpm

COPY front/package.json front/pnpm-workspace.yaml ./
RUN --mount=type=cache,id=wahrwelt-farm-pnpm,target=/pnpm/store pnpm install --no-frozen-lockfile --lockfile=false

COPY front/ ./
RUN pnpm build

FROM nginxinc/nginx-unprivileged:alpine

USER root
RUN apk upgrade --no-cache
USER 101

COPY docker/front/app.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8080
COPY --from=project /LICENSE /usr/share/licenses/wahrwelt/LICENSE
