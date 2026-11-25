# BSCPCRAFT docs

Сайт документации BSCPCRAFT на Next.js и Fumadocs. Собирается в статический
каталог `out/`, серверный runtime для публикации не нужен.

## Локально

Требуется Node.js 20+ и npm.

```sh
npm ci
npm run dev
```

Откройте http://localhost:3000. Страницы лежат в `content/docs/`:

- `(guides)` — гайды;
- `sdk` — документация SDK.

## Сборка

```sh
npm run build
```

Результат — `out/`. Проверить локально:

```sh
npm run start
```

## Публикация

`out/` можно раздать любым статическим хостингом: Cloudflare Pages, Vercel,
Netlify или обычный nginx. Сайт рассчитан на корень домена; для публикации в
подкаталоге нужны `basePath` и `assetPrefix` в `next.config.mjs`.
