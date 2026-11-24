# План публикации

## Рекомендуемая организация

Один репозиторий `SCServer` для собственного кода, документации и Issue tracker.
Компоненты разделены каталогами; отдельные репозитории потребуются при независимых владельцах и релизных циклах.
Рабочий каталог с играми и десятками накопленных тестов целиком не импортируется в Git.

1. Проверить `NOTICE.md`, происхождение кода и выбрать лицензию, когда владелец будет готов.
2. Свести исходники с проверенными слоями сервера; закрыть блокеры из STATUS.
3. Проверить исходники в новой папке: аудит, сборка, тесты; затем игровой smoke-test на отдельной базе.
4. Создать публичный GitHub repository `DeTTK/SCServer` без автоматически добавляемого LICENSE.
5. Включить Issues, Discussions, private vulnerability reporting и push protection.
6. Для main включить PR review и обязательный независимый CI. Не выдавать PR job секреты сервиса.
7. Публиковать теги и changelog с точным перечнем проверок.

## Первый push после ревью

Выполняется **в этом чистом каталоге**, а не в рабочей папке игры:

```sh
git init -b main
git status
python scripts/audit_public.py
python scripts/build.py test
git add .
git diff --cached --stat
git diff --cached
git commit -m "Prepare initial development snapshot"
git remote add origin <URL созданного репозитория>
git push -u origin main
```

Владелец выбрал `DeTTK/SCServer`, публичную разработку и отсутствие лицензии.
Статус фактической загрузки и commit указываются отдельно от результатов локальной сборки.

## Каналы лаунчера

Лаунчер выбирает канал по имени ветки `build-<channel>`. Доступны `main`, `test` и `launtest`;
список задан в `SELECTABLE_CHANNELS` (`launcher/crates/launcher-core/src/channels.rs`), а публикация
разрешена в `publish-game.yml`, `source-checks.yml` и `scripts/delivery.py`.

Каждый канал обновляется по подписанному `delivery.json` с полным списком файлов и SHA-256.
Лаунчер скачивает только файлы, которых нет в общем кэше `data/cache/channel-files/<sha256>`,
и собирает неизменяемую ревизию в `data/channels/<hash-ветки>/revisions/<revision>`.
Поэтому переключение `launtest` ↔ `main` не смешивает файлы и не перезаписывает старую ревизию:
неизменившиеся файлы переиспользуются, изменённые берутся из кэша, а данные игрока и моды
остаются в каталоге своей ветки. Ветки `build-*` генерируются автоматически и вручную не правятся.

## Releases

В Git хранятся исходники и маленькие текстовые manifests. Зависимости скачиваются по закреплённым хешам.
После проверки состава и происхождения в Releases можно размещать собственные GUI,
серверный JAR, SDK, agents, checksums и release notes. В архиве перечисляются исходный commit,
версия частной базы, схема протокола, что проверено и как откатиться.

По просьбе владельца готовый комплект и карта опубликованы отдельно от исходников:
[локалка и установщик](https://github.com/DeTTK/SCServer/releases/tag/local-ready-2026.11.20),
[исходная карта](https://github.com/DeTTK/SCServer/releases/tag/original-map-2026.11.20).
В Git игровые бинарники не добавляются. В Releases нельзя подмешивать рабочие сохранения,
профили пользователей, токены или сертификаты сервиса.

Политики платформы проверяются перед релизом:
[крупные файлы](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github),
[Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases),
[push protection](https://docs.github.com/en/code-security/how-tos/secure-your-secrets/prevent-future-leaks/enable-push-protection).
