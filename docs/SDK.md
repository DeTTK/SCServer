# Моды и SDK

Публичный API модов — `szmods.api` (версия `1`). Мод — обычный Java-класс с интерфейсом `ClientMod`, упакованный в JAR с файлом `stalcraft-mod.properties`. Мод работает только на клиенте; серверного API модов нет.

API лежит в `sdk/api/szmods/api/`, его копия — в `client/mod-runtime/src/szmods/api/`; файлы должны совпадать байт в байт. Пример мода — `sdk/example/`.

## Что где лежит

| Путь | Назначение |
|---|---|
| `sdk/api/szmods/api/` | публичный API `1`: `ClientMod`, контексты, регистры |
| `sdk/example/` | `HelloMod` и пример манифеста |
| `client/mod-runtime/src/szmods/runtime/` | загрузчик, регистры в памяти, call sites хуков |
| `client/command-completion/` | мод подсказок команд, который ставит установщик |
| `mods/<name>/` | десять исходных модов; рецептов сборки в `build.py` нет |
| `docs/QUICKSTART.md` | установка и запуск локалки |

## Первый мод

1. Скопируйте `sdk/example/src/example/HelloMod.java` в свой пакет и поменяйте сообщения.
2. Поправьте `sdk/example/stalcraft-mod.properties`: `id`, `name`, `version`, `main`.
3. Соберите `python scripts/build.py sdk` — получатся `build/mod-api.jar` (только API) и `build/hello-mod.jar` (пример).
4. Положите JAR в папку `mods` совместимого клиента и перезапустите его.

Имя выходного JAR в `build.py sdk` фиксировано: `hello-mod.jar`, даже если мод переименован. Для своего проекта проще компилировать `javac --release 17 -cp build/mod-api.jar` и класть в JAR свой манифест.

## Манифест

Имя файла — ровно `stalcraft-mod.properties` в корне JAR. Загрузчик ищет его по этому имени и без него JAR пропускает.

```properties
id=hello-mod
name=Hello Mod
version=1.0.0
api=1
main=example.HelloMod
```

- `id` — обязателен, `[a-z][a-z0-9_-]{0,63}`. Совпадение с ID встроенного мода или второго внешнего JAR отключает мод.
- `api` — обязателен и равен `1`. Другое значение — мод пропускается, совместимости между версиями API нет.
- `version` — обязателен, формат не проверяется.
- `main` — обязателен; класс должен существовать в этом JAR, реализовывать `szmods.api.ClientMod` и иметь публичный конструктор без аргументов.
- `name` загрузчик не читает: это подпись для людей и сторонних инструментов.

## Загрузка

Загрузчик — `szmods.runtime.ModLoader`. Каталог берётся из системного свойства `sz.mods.dir` (по умолчанию `mods`), которое выставляет лаунчер.

- Сканируются все `*.jar` в каталоге, в порядке имени файла. Ошибка одного JAR не мешает остальным.
- `mods/enabled.properties` отключает моды без удаления файла: строка `id=false`. Отсутствие ключа означает `true`.
- Системное свойство `allowed-mods` указывает файл со списком ID через запятую; если оно задано, загружаются только перечисленные моды.
- При `-Dsz.launcher.memory=true` каталог не сканируется: JAR приходят из памяти лаунчера (`getRemoteMods()` у системного загрузчика). Этот режим включает только `desktop/legacy/LightLauncher.cs`; переносимый лаунчер (`desktop/src/Portable.cs`) задаёт `-Dsz.mods.dir` и читает обычный каталог.
- Лог пишется в stderr строками `[ExternalMods][<id>] ...` и в `mods/loader-<pid>.log`.
- Каждому моду создаётся каталог `mods/config/<id>/`. Туда пишется `settings.properties`; сам мод может хранить там свои файлы через `ModContext.configDirectory()`.

## Жизненный цикл

Все колбэки, кроме `onUnload`, выполняются на игровом потоке. Порядок при старте:

1. `onLoad(ModContext)` — загрузка и подготовка. Настройки и HUD ещё не зарегистрированы.
2. `registerSettings(SettingsRegistry)` — объявление вкладки и значений.
3. загрузка сохранённых значений `settings.properties` (уже объявленных ключей).
4. `registerKeybinds(KeybindRegistry)`.
5. `registerHud(HudRegistry)`.
6. `onClientReady(ClientContext)` — все регистры готовы, кадры ещё не идут.
7. Далее каждый кадр: `onFrame(ClientFrame)` и `onInput(InputSnapshot)`, плюс фиксированные тики `onTick`.
8. `onLegacyStage(stage, context)` — фазы штатного `LegacyModLoader`; приходят, когда их вызывает совместимый клиент.
9. `onClientHook(hook, args)` — по требованию call site.
10. `onUnload()` — при выгрузке мода и при завершении клиента, в обратном порядке загрузки.

Исключение в любом колбэке отключает только этот мод: запись в лог `DISABLED after <stage>`, вызов `onUnload`, закрытие его `URLClassLoader`. Ошибки `VirtualMachineError` и `ThreadDeath` пробрасываются дальше и валят клиент.

### Тики

`onTick` идёт фиксированным шагом **1/240 с** независимо от FPS. После каждого кадра загрузчик накапливает долг, ограничивает его 0.25 с и делает не более 60 шагов. То есть при провале кадра время не догоняется бесконечно, а симуляция мода остаётся детерминированной. `ClientTick.number()` растёт на каждом шаге, `deltaSeconds` уже ограничен.

`onFrame` получает реальный интервал кадра, ограниченный 0.25 с; на первом кадре он равен нулю. `onInput` вызывается раз в кадр с одним и тем же снимком.

## Потоки и блокировки

Колбэки вызываются под монитором `ModLoader`. Блокирующая операция в колбэке останавливает и другие моды, и игровой кадр. Долгую работу выносите в свой поток или пул, а результат публикуйте в `volatile`-поле и обрабатывайте в `onTick`/`onFrame`. Сетевые и файловые операции в колбэках допустимы только короткие; загрузчик сам делает их при старте (чтение JAR, `onLoad`).

Колбэк может реентерабельно обращаться к `ModLoader` (например, вызвать `ModLoader.clientHook`), это поддерживается.

## Настройки

`SettingsRegistry` создаёт вкладку в штатном экране настроек (ESC).

```java
public void registerSettings(SettingsRegistry settings) throws Exception {
    SettingsRegistry.SettingsTab tab = settings.registerTab("my-mod", "Мой мод");
    tab.section("general", "Основное");
    SettingsRegistry.BooleanSetting enabled =
        tab.booleanSetting("enabled", "Включено", true);
    SettingsRegistry.NumberSetting fov =
        tab.numberSetting("fov", "Угол обзора", 90, 60, 130);
    SettingsRegistry.ChoiceSetting mode =
        tab.choiceSetting("mode", "Режим", "Быстрый", List.of("Быстрый", "Точный"));
    enabled.onChange(value -> onEnabledChanged(value));
}
```

- ID вкладки и значений — тот же шаблон `[a-z][a-z0-9_-]{0,63}`; заголовок вкладки до 80 символов, значения — до 120. Дубликаты ID и выход числа за диапазон — исключение при регистрации.
- `booleanValue` / `number` / `choice` — устаревшая форма объявления без обратной связи; новые значения лучше брать через `booleanSetting` и т.п.
- `set(value)` проверяет диапазон, сохраняет файл целиком (атомарной заменой) и вызывает подписчиков `onChange`. `reset()` возвращает значение по умолчанию.
- Сохранённые значения из `settings.properties` имеют приоритет над значениями по умолчанию и применяются после `registerSettings`, но до `onClientReady`. Некорректное сохранённое значение молча игнорируется.
- Заголовки вкладок и строк попадают в родное окно настроек через `SettingsBridge`; при смене версии клиента мост может не найти классы и записать в лог одну ошибку, не ломая игру.

## Кнопки

```java
public void registerKeybinds(KeybindRegistry keys) {
    keys.register("toggle", "Включить мой мод", 70); // F
}
```

`KeybindRegistry.register` только объявляет кнопку: ID, подпись и код GLFW по умолчанию. Действие пишется отдельно в `onInput`, повторная регистрация того же ID — исключение.

Важное ограничение версии `1`: `InputSnapshot.keys()` заполняется фиксированным набором кодов — `32, 65, 67, 68, 69, 70, 81, 82, 83, 87, 88, 90, 340, 341, 342, 344, 345` (пробел, A, C, D, E, F, Q, R, S, W, X, Z, Shift и Ctrl). Остальные клавиши в снимок не попадают, поэтому `input.keyDown(75)` для K всегда `false`. Значение из `KeybindRegistry` тоже не прочитать: интерфейс умеет только объявлять, а UI переназначения кнопок в runtime нет. Для остальных клавиш используйте хук `input.key.v1` или собственный опрос GLFW на игровом потоке.

## HUD

```java
public void registerHud(HudRegistry hud) {
    hud.element("panel", "my-mod/panel", HudRegistry.Anchor.TOP_LEFT, 24, 24, 320, 64);
    hud.register("panel-draw", (renderer, width, height, partialTick) -> draw(renderer, width, height));
}
```

- `register(id, overlay)` — отрисовка на каждом вызове родного HUD-прохода; дубликат ID — исключение.
- `element(id, figmaNode, anchor, x, y, width, height)` — декларативное описание элемента для моста раскладки; само по себе ничего не рисует.
- `hide(channel)` / `setVisible(channel, visible)` помечают канал скрытым. Загрузчик отдаёт это через `hudHidden(channel)`; из коробки так работает только канал `crosshair`.
- `overlay.render(Object hud, ...)` получает родной объект HUD; его тип зависит от версии клиента.
- Отрисовка оверлеев вызывается из того же call site, что и `hud.crosshair.v1`: если совместимый клиент этот проход не вызывает, HUD мода не появится. В единственном call site этого репозитория (`RenderHooks.crosshair`) `partialTick` всегда `0`.

## Ввод

`InputSnapshot` неизменяем и содержит:

- `keys()` — `Map<Integer, Boolean>` по кодам GLFW, только из набора выше;
- `mouseButtons()` — кнопки мыши `0..7`;
- `axes()` / `buttons()` — оси и кнопки первого геймпада (`left-x`, `left-y`, `right-x`, `right-y`, `left-trigger`, `right-trigger`; `a`, `b`, `x`, `y`, `left-bumper`, `right-bumper`, `back`, `start`, `left-stick`, `right-stick`, `dpad-up`, `dpad-right`, `dpad-down`, `dpad-left`);
- `gamepads()` — все подключённые геймпады с их именами.

Оси в `[-1, 1]`, триггеры в `[0, 1]`. Значения возвращаются без исключений: неизвестный код клавиши, оси или кнопки даёт `false` / `0`.

`InputSnapshot` — снимок состояния, а не события. Для одного действия на нажатие храните предыдущее состояние. Пример: как FPV-дрон заменяет снимок своими «сырыми» клавишами, см. `mods/fpv-drones/src/fpv/FpvDroneMod.java`, `onInput`.

## Хуки onClientHook

`onClientHook(String hook, Object... args)` — единственный способ вмешаться в родные пути рендера, камеры и ввода. Контракт: `null` означает «не вмешиваюсь», родное поведение продолжается; первое непустое значение выигрывает, остальные моды этот хук уже не получают. Исключение отключает мод.

Аргументы — родные объекты конкретной сборки клиента (`Object`). Это осознанно: публичный API не тянет обфусцированные классы, но и стабильности типов между версиями клиента нет. Возвращаемые значения загрузчик проверяет и при несоответствии молча откатывается на родное поведение.

Порядок модов — порядок загрузки, кроме `camera.third-person.v1` и `camera.fov.v1`: там мод `fpv-drones` получает приоритет, чтобы полноэкранный вид дрона не перекрывался камерой от плеча.

Хуки, у которых в репозитории есть call site в `client/mod-runtime/src/szmods/runtime/`:

| Хук | Аргументы | Ожидаемый возврат |
|---|---|---|
| `input.key.v1` | `int key`, `boolean original` | `Boolean` — новое решение |
| `input.action-block.v1` | — | `Boolean.TRUE` — заблокировать действия |
| `input.ui-capture.v1` | — | `Boolean.TRUE`, пока интерактивное меню мода владеет указателем |
| `camera.perspective.v1` | `camera`, `original` | объект того же класса, что `original` |
| `camera.third-person.v1` | `player`, `eye`, `float yaw`, `float pitch`, `mode` | `double[3]` — позиция камеры |
| `camera.fov.v1` | `camera`, `float original` | `Number` в диапазоне 20…120 |
| `camera.native-view.v1` | `camera`, `matrix` | `Boolean.TRUE`, если мод сам выставил вид |
| `weapon.aim.v1` | `player`, `origin`, `end` | `double[3]` (только конец) или `double[6]` (начало и конец), конечные числа |
| `weapon.tracer-offset.v1` | `int shooter`, `int local` | `Boolean.FALSE` — отключить родной сдвиг трассера |
| `fp.screen-pass.v1` | `player` | `Boolean.TRUE` |
| `fp.effect-bone.v1` | `context`, `String bone` | `float[16]` — матрица |
| `hud.crosshair.v1` | `hud`, `int width`, `int height` | `Boolean.TRUE` — скрыть родной прицел |
| `hud.hide-native.v1` | — | `Boolean.TRUE` — скрыть родной HUD |
| `audio.local-player.v1` | `owner` | `Boolean` |
| `view.block-body-look.v1` | `game` | `Boolean.TRUE` — заблокировать поворот тела |
| `view.subject.v1` | `original` | объект того же класса |
| `player.movement-intent.v1` | `original` | объект того же класса — подменённое намерение движения |
| `scene.drones.v1` | `camera`, `float delta` | не используется |
| `video.before-post.v1` | — | не используется |
| `video.photo-filter.v1` | — | `Object` — профиль фильтра |
| `video.photo-enabled.v1` | — | `Boolean` |
| `video.photo-blend.v1` | `pipeline`, `profile`, `int width`, `int height` | `Boolean.TRUE` |
| `video.night-vision.v1` | `effect`, `parameters` | `Boolean.TRUE` |
| `chat.catalog.v1` | `connection`, `byte[]` | — |
| `chat.key.v1` | `chat`, `int key` | `Boolean.TRUE` — клавиша обработана |
| `chat.render.v1` | `chat` | — |
| `chat.replace-tab.v1` | — | `Boolean.TRUE` — подменить вкладку инвентаря |
| `fpv.item.usable.v1` | `definition` | `Boolean` |
| `fpv.item.use.v1` | `stack` | `Boolean` |
| `fpv.item.profile.v1` | `definition` | `Object` |
| `fpv.item.renderer.v1` | `stack` | `Object`; `null` оставляет родной |
| `fpv.item.place.v1` | `address`, `stack` | `Boolean` |
| `network.fpv.snapshot.v1` | `connection`, `byte[]` | — |
| `network.vehicle.snapshot.v1` | `connection`, `byte[91]` | `Boolean.TRUE` |
| `network.vr.weapon.v1` | `connection`, `byte[]` | — |
| `vr.render-world.v1` | `renderer`, `float partial` | `Boolean.TRUE` — отменить родной проход |
| `vr.render-view-ready.v1` | `renderer` | — |
| `vr.projection.v1` | `camera`, `matrix` | — |
| `vr.effects-prepared.v1` | — | `Boolean.TRUE` — эффекты уже подготовлены |
| `vr.second-eye.v1` | — | `Boolean.TRUE` — второй глаз уже отрисован |
| `vr.desktop-ui.v1` | `renderer`, `float partial` | — |
| `vr.action-focus.v1` | — | `Boolean.TRUE` — забрать фокус действий |

Хуки, которые встречаются только в исходниках `mods/` и не имеют call site в этом репозитории (`player.animation.v1`, `model.actor-visible.v1`, `model.body-visible.v1`, `model.mesh-visible.v2`, `fp.body-visible.v1`, `camera.director-active.v1`, `hud.arc.*`, `replay.*`, `weapon.workbench-*`), обслуживает совместимый клиент. Опираться на них в стороннем моде рискованно: их наличие и типы аргументов не проверяются этим репозиторием.

## Пакеты szmods.bridge.*

Сетевые пакеты модов объявлены в приватной части совместимого клиента, а не в SDK. Мод создаёт объект по полному имени и отправляет его штатным конвертом. Используются: `CommandRequest`, `CommandCatalog`, `MedicineUse`, `FpvInput`, `FpvSnapshot`, `PlayerAnimation`, `VehicleInput`, `VehicleSnapshot`, `VrWeapon`, `VrWeaponReply`, `EnvironmentInput`, `EnvironmentSnapshot`, `NpcEditorInput`, `NpcEditorSnapshot`, а также адаптер `LegacyAdapter`, через который загрузчик подключает мод к родному списку.

Со стороны сервера эти имена — часть контракта (`server/src/main/java/szserver/net/*`, `docs/COMPATIBILITY_NAMES.md`). Переименование ломает уже установленные моды.

## Слой szmods.runtime

Компилированные моды десяти проектов в `mods/` обращаются к `szmods.runtime.*` напрямую: `ModLoader.clientHook`, `NativeFlightHooks.actionBlocked`, `FpvPackets`, `AnimationHooks`, `ClientHits` и т.п. Это полу-публичный слой: он есть в исходниках и работает, но его сигнатуры не входят в API `1` и могут меняться без смены версии API. Минимум, который стоит знать:

- `ModLoader.clientHook(hook, args)` — вызвать хук из своего кода (так делают камеры и реплеи).
- `FpvPackets.rawCodec(packet)` — единственная точка, по которой совместимый клиент решает, что пакет относится к моду и его должен разбирать мод, а не родной кодек. Патчи клиента привязываются к этому методу по имени и дескриптору, поэтому менять его сигнатуру нельзя.
- `ClientHits.capture(shot)` / `write(shot, out)` — снимок попадания на момент выстрела; сервер принимает повторную отправку только с тем же попаданием. Счётчики `captured` / `failures` отдаёт диагностический мост bootstrap; сами call sites находятся в патчах приватного клиента.
- `FpvAudioHooks.setListener(double[12])` — позиция, скорость, направление и «верх» слушателя для нативного аудиопотока.
- `VrFrameHooks.interval(native)` — при `-Dsz.vr.active=true` отдаёт `0`, чтобы пейсингом управлял OpenXR.

## Моды с javaagent

Три проекта в `mods/` (`network-replay`, `npc-director`, `world-environment`) кроме JAR содержат `premain`-агент на `java.lang.instrument`, который трансформером правит байткод уже загружаемых классов. Такой агент подключается через `-javaagent:` в аргументах JVM, а не через папку `mods`, поэтому установщик его не ставит, и `build.py` его не собирает.

Такой подход опирается на конкретные имена и дескрипторы методов. Например, агент NPC-директора перехватывает `szmods.runtime.FpvPackets.rawCodec(Ljava/lang/Object;)Z`; переименование или смена сигнатуры тихо отключает патч. Для обычного мода используйте `onClientHook`: агент нужен только там, где хука нет.

## Ограничения

- Мод работает только в совместимом клиенте: один JAR в произвольной сборке игры не даст ни хуков, ни настроек, ни сети.
- Внутренние классы игры привязаны к версии клиента. Любой reflection по обфусцированным именам и числовым ID нужно писать защитно: искать метод по имени, аргументам и возвращаемому типу, проверять `static`/`instance` и переживать отсутствие класса.
- HUD-оверлеи и часть хуков зависят от того, вызывает ли их совместимый клиент; молчаливое отсутствие эффекта — ожидаемое поведение, а не ошибка загрузчика.
- Загрузчик не изолирует моды друг от друга по правам: мод видит классы игры и других модов. Ставьте только доверенные JAR.
- Имя `stalcraft-mod.properties`, ID хуков, имена bridge-пакетов и имена системных свойств (`sz.mods.dir`, `sz.launcher.memory`, `allowed-mods`, `sz.vr.active`) — контракт, а не деталь реализации.

## Сборка и проверка

```sh
python scripts/build.py sdk                  # mod-api.jar + hello-mod.jar
python scripts/build.py command-completion   # command-completion.jar + CompletionTest
python scripts/build.py test                 # SDK, сервер и серверные наборы
```

`build.py sdk` компилирует API без зависимостей и пример против готового `mod-api.jar`. Тестов самого API нет: контракт проверяется сборкой примера и мода автодополнения, а поведение хуков — ручным прогоном в совместимом клиенте. Успешная компиляция не доказывает, что мод загрузится или отрисуется.

## Что читать дальше

- `docs/QUICKSTART.md` — установка локалки, свой `server.jar`, диагностика падений.
- `docs/ARCHITECTURE.md` — где мод стоит в схеме клиента и сервера.
- `docs/COMPATIBILITY_NAMES.md` — какие имена менять нельзя.
- `docs/STATUS.md` — что проверено в игре на 22.11.2026.
