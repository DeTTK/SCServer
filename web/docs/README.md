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

### GitHub Pages с доменом

1. Settings → Pages → Source: **GitHub Actions**.
2. Укажите custom domain в Settings → Pages.
3. Создайте Actions variable `SITE_URL`, например
   `https://docs.example.com` (Settings → Secrets and variables → Actions →
   Variables).
4. Отправьте изменение в `web/docs/**` в `main` или запустите workflow
   **Docs site** вручную.

Workflow `.github/workflows/docs-pages.yml` собирает сайт, добавляет `.nojekyll`
и `CNAME`, затем публикует `out/` через GitHub Pages. `basePath` не нужен.
