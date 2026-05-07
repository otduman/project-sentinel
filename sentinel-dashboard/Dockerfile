# -- Build stage ---------------------------------------------------------------
# Pinned to specific Node version — see VERSIONS.md before bumping.
FROM node:20.18-alpine AS builder
WORKDIR /app

COPY package*.json ./
RUN npm install

COPY . .
RUN npm run build

# -- Runtime stage -------------------------------------------------------------
# Pinned nginx — see VERSIONS.md before bumping.
FROM nginx:1.27-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
