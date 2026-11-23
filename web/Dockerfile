FROM node:22.15.0-alpine AS build
WORKDIR /web
COPY package*.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.28.0-alpine
COPY --from=build /web/dist /usr/share/nginx/html
