# launtest → build-launtest

Исходники разрабатываются в `launtest`. После успешных тестов workflow `Publish launtest game` собирает сервер, startup guard, resource compatibility agent, character scale и command-completion, формирует подписанный список отдельных файлов и публикует его в `build-launtest` того же репозитория.

Игрок получает изменения через Rust-лаунчер без Git и GitHub-токена. Загрузка привязана к одному commit build-ветки; подпись проверяется встроенным публичным ключом. Непрошедшая тесты или вытесненная более новым push сборка не публикуется. Источники и build-файлы не смешиваются. `main` workflow не публикует.

Первая версия канала требует установленную основу из `bootstrap/base.lock.json`; это обновление поверх существующего комплекта, не новая автономная раздача всех игровых файлов. Карта и Java не скачиваются заново при каждой правке. Исходники модов без воспроизводимого рецепта остаются source-only и не выдаются за доступные бинарные сборки.

Локальная проверка: `python scripts/delivery.py prepare`. CI дополнительно выполняет `python scripts/delivery.py publish`, используя `LAUNTEST_DELIVERY_PRIVATE_KEY` из Actions Secrets. В репозитории хранится только `public-key.txt`. Приватный ключ запрещён в исходниках, build-ветке и логах.
