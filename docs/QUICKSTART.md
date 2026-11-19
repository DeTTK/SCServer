# Быстрый старт

## Разработка без игровых файлов

Нужны Git, Python 3.10+ и **JDK 17** с `java` и `javac` в `PATH` либо `JAVA_HOME`.
Команды выполняются из корня checkout; путь может содержать пробелы.

```sh
python scripts/audit_public.py
python scripts/build.py test
```

Сборка создаёт собственный `server.jar`, API и HelloMod. Тесты проверяют границы регионов,
CRC/повреждения и потоковое восстановление журнала под ограничением heap 64 МиБ.
Журнал на 98 МиБ создаётся во временном каталоге и удаляется после теста.
Итоги находятся в `build/verification.json`; их нельзя выдавать за проверку сетевого входа.

## Первый мод

1. Изучите `sdk/example/src/example/HelloMod.java` и `sdk/example/stalcraft-mod.properties`.
2. Измените ID, main class и сообщения.
3. Выполните `python scripts/build.py sdk`.
4. Для проверки в совместимом частном клиенте положите `build/hello-mod.jar` в его папку `mods`.

Callbacks API v1 описаны в `sdk/api/szmods/api/ClientMod.java`.
`game` в контексте — внутренний объект конкретного билда, его ABI нестабилен.

## Игровой запуск через существующую проверенную поставку

Игровой комплект подключается отдельно; его получение и права на использование в этот репозиторий не входят.
Это тот же формат папок `Client`, `Server`, `Map`, который используется в текущей локалке.

```sh
python scripts/local.py configure --client-bundle "C:/LocalGame/Client" --server-bundle "C:/LocalGame/Server" --game "C:/Games/BSCPCRAFT" --map "C:/LocalGame/Map"
python scripts/local.py doctor
python scripts/local.py open-server
python scripts/local.py open-client
```

На Windows откроются штатные окна комплекта. В ServerManager выберите Map и IP компьютера.
В Launcher задайте этот IP:29545, логин и установленную игру.
Для LAN нужны TCP 29545/29546 и TCP/UDP 29547. Admin API оставляется на loopback.
Сервер останавливается кнопкой сохранения. Изменения мира и персонажи остаются в `Server/data`.

`local.py` запускает подключённый проверенный бинарный комплект. Он не подменяет его свежесобранным `build/server.jar`.
Для запуска нового source JAR используйте следующий раздел.
Разработчик не должен считать успешный запуск старого бинарника проверкой своих новых исходников.

## Запуск сервера из своих исходников

Нужна совместимая приватная папка `Server` из локальной поставки и исходная `Map`.
Скрипт не скачивает игру и не подключается к production.

```sh
python scripts/import_private_resources.py --server-jar "C:/LocalGame/Server/server.jar"
python scripts/build.py server --private-resources
python scripts/run_server.py --bundle "C:/LocalGame/Server" --map "C:/LocalGame/Map"
```

Импортёр извлекает ровно четыре ресурса по закреплённым SHA-256, без классов.
Новый JAR запускается из `build/server.jar`; игра и map читаются из внешнего комплекта.
Состояние нового сервера хранится отдельно в `.local/server-state/data`, оригинальная карта не меняется.
Один state защищён от параллельного запуска OS-lock. Консоль показывает серверные логи.
Для остановки нажмите Ctrl+C и дождитесь сохранения/завершения Java.

По умолчанию запуск локальный: игровой HTTP `127.0.0.1:29544`, gateway 29545,
world 29546, admin health `http://127.0.0.1:29644/health`.
Другой IP задаётся `--host`, базовый порт — `--port`; при смене порта меняются также +1/+2/+100/+104.
Launcher клиента должен использовать тот же игровой HTTP адрес.
Административный токен созданной базы остаётся в `.local/server-state/data/admin-token.txt`.

После `build.py test` серверный JAR снова будет без игровых ресурсов: перед игровым
запуском повторите `build.py server --private-resources`. Проверка run_server не даст запустить
JAR без нужных ресурсов. Отчёт smoke не означает проверки полноценного боя клиентом.

## Windows GUI из исходников

```sh
python scripts/build.py desktop
```

Используется системный .NET Framework C# compiler. Получатся `build/desktop/Launcher.exe`
и `ServerManager.exe`. Это GUI, а не полный установочный комплект: рядом должны быть
подготовленные Java, runtime, ресурсы и конфигурация из отдельной поставки.
Legacy `SZCustom` с подписанной загрузкой требует сертификат оператора, публичный ключ релизов
и встроенный compatibility agent. Эти материалы и данные рабочего сервиса не экспортируются.
