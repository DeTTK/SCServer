# AGENTS.md — SCServer / BSCPCRAFT

Публичное название проекта — **BSCPCRAFT / Бскпкрафт**, интеграция — **BSCP**. Имя репозитория остаётся `SCServer`.
Это исходники локального сервера и клиентской обвязки для игры BSCPCRAFT (stalzone.gay), собранные из собственного кода поверх приватной совместимой игровой базы. Лицензия намеренно не выбрана (файла `LICENSE` нет), см. `NOTICE.md`.

В репозитории **нет** обычных манифестов сборки (`pom.xml`, `build.gradle`, `package.json`, `pyproject.toml`, `Cargo.toml`, `Makefile`). Сборка — это Python-скрипты из `scripts/`, которые вызывают `javac`/`java` и Windows `.NET Framework` компилятор напрямую.

## Быстрый старт для агента

```sh
python scripts/audit_public.py # preflight: бинарники, секреты, пути рабочих машин
python -m unittest discover -s scripts/tests # 5 Python-тестов (game layout)
python scripts/build.py test # сборка server.jar + SDK + command-completion, 14 серверных наборов + CompletionTest
python scripts/build.py sdk # build/mod-api.jar, build/hello-mod.jar
python scripts/build.py server # build/server.jar (без игровых ресурсов)
python scripts/build.py server --private-resources # + 5 закреплённых приватных ресурсов
python scripts/build.py command-completion # build/command-completion.jar + его тесты
python scripts/build.py desktop # только Windows: build/desktop/*.exe
python client-compat/character-scale/build.py # build/character-scale.jar + ScaleTests
```

Требуется **JDK 17** (`java`/`javac` в `PATH` или `JAVA_HOME`; компиляция идёт с `--release 17`, поэтому подходит и более новый JDK) и **Python 3.10+**. Для `desktop` обязательны Windows и `csc.exe` из .NET Framework 4.0.30319.
Быстрая проверка окружения: `python scripts/audit_public.py` → `result: PASS`, `python -m unittest discover -s scripts/tests` → 5 тестов OK, `python scripts/build.py test` → exit 0 и `build/verification.json`.

## Технологический стек

- **Сервер:** Java 17, без фреймворков; TCP/UDP-сеть на `java.net` (`ServerSocket`, `DatagramSocket`, `HttpServer`), JSON через Gson, сжатие через zstd-jni. Один entrypoint — `szserver.ServerMain` (аргумент — путь к `server.properties`).
- **Зависимости** закреплены в `dependencies.lock.json` (Maven URL + SHA-256) и скачиваются в игнорируемый `.local/deps`:
`gson-2.13.2.jar`, `zstd-jni-1.5.0-5.jar`. Без совпадения хеша сборка падает.
- **Клиентская часть:** Java-агенты/трансформеры (ASM через `jdk.internal.org.objectweb.asm`, `--add-exports`), bootstrap на штатной JVM; исходники клиента в репозитории, но приватный игровой ABI не входит.
- **Desktop:** C# под .NET Framework (WinForms), компилируется `csc.exe`, не MSBuild-проект.
- **Скрипты:** Python 3 только стандартная библиотека; стиль — плотные строки, минимум отступов.
- Сборка и тесты не используют Maven/Gradle/Node/npm. Клиентские исходники обращаются также к Kotlin, LWJGL и ImGui, но этих компонентов в независимом CI-рецепте нет (`THIRD_PARTY.md`).

## Структура репозитория

| Каталог | Что внутри |
|---|---|
| `server/src/main/java/szserver/` | основной сервер: `net` (95 файлов), `world` (117), `client` (123), `commands` (49), `inventory` (36), `persistence` (20), `migration` (18), `util` (8), `npc` (4), `http` (1), `ServerMain.java` |
| `server/src/main/java/{environment,scvehicles}/protocol` | сетевые DTO для окружения и транспорта |
| `server/src/test/java/` | 15 Java-тестов, каждый — обычный `main` с `check(...)`/`AssertionError`, без JUnit; 14 из них запускает `build.py test` |
| `shared/player-scale/src/playerscale/` | общий протокол размера персонажа (компилируется и в серверный JAR) |
| `client/bootstrap/`, `client/startup/` | запуск штатной JVM, экран загрузки, headless control bridge |
| `client/mod-runtime/src/szmods/` | SDK-API (`api`) и runtime модов (`runtime`, `ModLoader`, hooks) |
| `client/resource-compat/src/gamecompat/` | ASM-трансформеры чтения новых игровых ресурсов (MTL, shader hooks, UDP policy, UI) |
| `client/command-completion/` | мод подсказок команд; `mods/command-completion` — его копия |
| `client-compat/character-scale/` | premain-агент масштаба персонажа, хеши классов, `build.py` |
| `desktop/src/` | установщик (`Setup.cs`), общий лаунчер (`LocalLauncher.cs`), `Launcher`/`ServerManager` (`GameLayout.cs`, `Portable.cs`) |
| `desktop/server.template.properties` | шаблон `server.properties` с маркерами `@ROOT@ @MAP@ @HOST@ @HTTP@ @GAME@ @WORLD@ @ADMIN@ @DEV@` |
| `sdk/api/szmods/api/` | публичный API модов v1 (`ClientMod` и регистры) |
| `sdk/example/` | пример `HelloMod` + `stalcraft-mod.properties` |
| `mods/<name>/` | 10 исходных модов; только исходники, в CI не собираются (`mods/modules.json`) |
| `scripts/` | `build.py`, `run_server.py`, `local.py`, `import_private_resources.py`, `game_layout.py`, `audit_public.py` |
| `config/` | `local-paths.example.json`, `private-resources.lock.json` |
| `docs/` | русская документация: QUICKSTART, ARCHITECTURE, STATUS, TESTING, PRIVATE_INPUTS, PUBLISHING и др. |
| `validation-*.json` | проверенные отчёты о реальных игровых прогонах (не создавать заново без нужды) |
| `source-manifest.json` | происхождение 851 экспортированного файла: путь-источник, `adjusted`, SHA-256; `licenseChosen=false`, `originReviewComplete=false` |

Игнорируются Git: `.local/`, `build/`, `dist/`, все `.jar/.dll/.exe/.zip`, карты, сохранения, логи, ключи. Сборка кладёт артефакты только в `build/`.

## Архитектура и правила кода

- **Мир:** `szserver.world.WorldLoop` — один owner-поток, фиксированные шаги **20 Гц** (интервал 50 000 000 нс), без catch-up-всплесков; очередь задач ограничена 256, бюджет на шаг 64 задачи / 5 мс. IO, загрузка чанков и тяжёлые фабрики — вне сетевого event loop и owner-потока (`szserver.util.Workers` — пулы с именованными daemon-потоками).
- **Сеть:** `szserver.net.GameServer` — единственный владелец транспорта, сессий и боевого состояния; надёжные окна и дедлайны тиков, нативные сериализаторы прогреваются до открытия портов. Долгие операции входа/хранилища — на bounded login/storage workers.
- **Команды/сохранение:** `szserver.commands.CommandService` — один владелец durable-команд; принятые команды реплеятся и идемпотентны. `szserver.persistence.Journal` — единственный писатель журнала (magic `0x535A4A31` — «SZJ1», CRC32, `MAX_RECORD=65536`); ошибка CRC валит старт, отбрасывается только незавершённый хвост. Тик-потоки в файл не пишут.
- **Инвентарь:** `InventoryJournal` + `WorldItemLedger` — единственный writer; `WorldItem`/`ItemCollisionScene` — физика предметов на земле, `LootboxCatalog`/`LootboxOpening` — кейсы.
- **Адаптеры:** обфусцированные игровые имена, числовые Class ID, ABI и reflection живут только в adapters/bridge (`szserver.client.*`, `client/*`, `client-compat/*`). Серверным сервисам отдаются обычные Java-типы. Reflection обязан выбирать метод по имени, аргументам, возвращаемому типу и static/instance — одного `getMethod(name)` недостаточно (перегрузки по возвращаемому типу реальны).
- **FPV:** полёт, столкновения и мотор симулирует клиент пилота; сервер ведёт жизненный цикл, ретрансляцию и подписки на чанки.
- **Форматы:** карта и сетевые схемы меняются только с версией и миграцией; старые числовые ID автоматически не переносятся. Legacy decoded-классы не накладываются поверх promoted runtime автоматически.
- **Стиль:** файлы очень плотные — несколько операторов в строке, короткие однострочные методы, минимум пустых строк; комментарии на английском, документация на русском. Следуйте стилю окружающего файла. Кодировка UTF-8, переводы строк LF (кроме `.ps1` → CRLF, см. `.gitattributes`). `javac` запускается с `-encoding UTF-8 -proc:none` и argfile.

## Тесты

```sh
python -m unittest discover -s scripts/tests # scripts/tests/test_game_layout.py
python scripts/build.py test
```

- `build.py test` компилирует сервер, SDK и command-completion, затем запускает 14 серверных наборов (классы `szserver.*`), тест `completion.CompletionTest` и пишет `build/verification.json`. Тесты синтетические: игровые файлы и чужие сохранения не нужны.
- Покрытие: границы/CRC регионов, потоковое восстановление журнала (12 047 проверок, ~98 МиБ, heap 64 МиБ), кейсы, физика и журнал предметов на земле, размер персонажа, миграция сетки инвентаря, аномалии, gateway retry и 72 полных цикла аутентификации (HTTP → gateway → world, Unicode-логины).
- `server/src/test/java/szserver/client/ActorAnomalyMotionTests.java` в `build.py test` **не входит**: ему нужны приватные игровые ресурсы.
- Smoke-тест в настоящем клиенте — отдельный ручной прогон с отключённым звуком и заблокированным физическим вводом (включая Raw Input) и скрытым окном. Успешная компиляция и `build.py test` **не** доказывают вход в мир, сетевую сессию или визуальные окна. Результаты таких прогонов фиксируются в `validation-*.json` и `docs/STATUS.md`.
- CI: `.github/workflows/source-checks.yml` на push/PR/dispatch, матрица ubuntu-24.04 + windows-2022, Temurin 17: `audit_public.py` → unittest → `build.py test` → `client-compat/character-scale/build.py`; `build.py desktop` только на Windows. Автоматического скачивания игровых файлов в CI нет.

## Запуск сервера и игры (приватные входные данные)

Исходники без внешних файлов собираются и тестируются, но **игровой запуск** требует приватный совместимый комплект `Client/Server/Map` и установленную игру. Эти данные в Git не входят (`docs/PRIVATE_INPUTS.md`).

```sh
python scripts/import_private_resources.py --server-jar "C:/LocalGame/Server/server.jar"
python scripts/build.py server --private-resources
python scripts/run_server.py --bundle "C:/LocalGame/Server" --map "C:/LocalGame/Map"
```

- Импортёр копирует ровно **5** ресурсов из `config/private-resources.lock.json` (`szserver/inventory/lootbox-tables.json.gz`, `szserver/weather-default.json.zst`, `szserver/npc/npc-editor-defaults.json`, `szserver/client/npc-visual-profiles.json`, `assets/bundle/feature_flags.json`) в `.local/server-resources`; классы не копируются; несовпадение SHA-256 — ошибка.
- `run_server.py`: состояние в `.local/server-state/data` (отдельно от пользовательских сохранений), OS-lock против параллельного запуска, читает Map только для чтения, собирает `server.properties` из `desktop/server.template.properties`. По умолчанию `127.0.0.1:29544`: game HTTP, gateway +1, world +2, admin +100 (`http://127.0.0.1:29644/health`), dev +104. Админ-токен — `.local/server-state/data/admin-token.txt`. Остановка — Ctrl+C и дождаться сохранения/завершения Java.
- `build.py test` **пересобирает `server.jar` без ресурсов** — перед игрой снова выполните `build.py server --private-resources`; `run_server.py` проверит JAR и откажется запускать.
- `scripts/local.py configure|doctor|open-server|open-client` подключает готовый бинарный комплект (пути в `.local/paths.json`), не подменяя его своим `build/server.jar`. Windows-only для `open-*`.
- `game_layout.py` / `desktop/src/GameLayout.cs` распознают два корня игры: лаунчер (`classes.jar`, `assets`, `modassets`) и Steam (`assets`, `modassets` + `bin_ru`/`bin_global` с `classes.jar` и `patchassets/assets/bundle/configs.xeon`). Варианты `bin_ru`/`bin_global` не смешиваются.
- Типовые ошибки: `resident_map_marker_binding` (сохранения от другой карты) и нехватка native-памяти решаются только возвратом карты/отдельной `data.directory`, см. `docs/QUICKSTART.md`.

## Моды и SDK

- Публичный API — `sdk/api/szmods/api/ClientMod.java` (v1), копия в `client/mod-runtime/src/szmods/api/`; файлы синхронны.
- Загрузчик `szmods.runtime.ModLoader` читает каталог `mods` (свойство `sz.mods.dir`), JAR с `stalcraft-mod.properties` (`id`, `name`, `version`, `api=1`, `main`). Имя файла `stalcraft-mod.properties` менять нельзя.
- `python scripts/build.py sdk` даёт `build/mod-api.jar` и `build/hello-mod.jar` (имя JAR фиксировано, даже если мод переименован). `python scripts/build.py command-completion` даёт `build/command-completion.jar` и прогоняет `completion.CompletionTest`.
- В `mods/` десять модов (`arc-hud-prototype`, `better-third-face`, `cinematic-camera`, `command-completion`, `fpv-drones`, `network-replay`, `npc-director`, `sc-vehicles`, `weapon-workbench`, `world-environment`) — только исходники, рецептов в `build.py` нет, в CI не собираются. Внутренние игровые классы нестабильны между версиями клиента.
- Мод размера персонажа (`client-compat/character-scale` + `shared/player-scale`) — не мод из `mods/`: premain-агент, проверяет SHA-256 семи классов клиента (`native-hashes.json`) и при несовпадении отключается с записью в лог. Сборка: `python client-compat/character-scale/build.py [--runtime C:/Local/Client/runtime]`; используется `--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED`.

## Безопасность и публикация

- Никогда не коммитьте: рабочие токены, ключи подписи/TLS, сертификаты, `Server/data` (персонажи, журналы, checkpoint, admin token), игровые ассеты/карты, DLL/дампы, `classes.jar`, скачанные reference-репозитории, сырые логи с идентификаторами. `.gitignore` и `audit_public.py` это проверяют, но аудит — эвристика, а не доказательство чистоты.
- `audit_public.py` падает на бинарных расширениях, файлах > 2 МиБ, NUL-байтах, symlink/junction, PAT-шаблонах (private key, Telegram/GitHub token, `password=`, `api_key=`, `secret=`), абсолютных путях с буквой диска Windows (регулярка `[DE]:/…` в скрипте). При добавлении файлов прогоняйте его.
- Локальный сервер рассчитан на доверенную сеть. Логин по одному имени и права `game.playerPermission=admin` из локалки — не политика для открытого интернета. Изменения аутентификации, прав и сохранений требуют отдельного тестирования (`SECURITY.md`).
- Публичный PR: воспроизводимое изменение в исходниках/сборочных рецептах (ручная замена `.class` не принимается), проверенное поведение, версия/хеш приватной базы и запись воспроизведения. Не прикладывайте DLL, дампы, архивы, токены, чужие карты и сырые логи (`CONTRIBUTING.md`, `.github/pull_request_template.md`).
- При переносе приватной базы сохраняйте закреплённые реализации accessory physics `1262384494`, `212440292`, `108847398`, `1536296909`; замена требует отдельной проверки.

## Брендинг и совместимость

При смене названия **не** переименовываются внешние Java-пакеты, native entry points, DLL/JVM properties, имена manifest-файлов модов, сигнатуры replay/registry, диагностический ID HTTP-службы и пространства имён аккаунтов — это контракт с клиентом, установленными модами и сохранёнными данными (`docs/COMPATIBILITY_NAMES.md`).

## Что читать дальше

- `docs/QUICKSTART.md` — установка, Steam/Wine, запуск своего JAR, диагностика падений.
- `docs/SDK.md` — моды: манифест, жизненный цикл, настройки, HUD, ввод, таблица хуков.
- `docs/ARCHITECTURE.md` — схема и границы модулей.
- `docs/TESTING.md` — что именно проверяет `build.py test` и что остаётся на ручной игровой прогон.
- `docs/PRIVATE_INPUTS.md` — перечень приватных входов и почему их нельзя импортировать автоматически.
- `docs/STATUS.md` — хронология проверенных изменений и известных ограничений (на 22.11.2026).
- `docs/ROADMAP.md`, `docs/PUBLISHING.md`, `NOTICE.md` — лицензия, публикация, оставшиеся задачи.

