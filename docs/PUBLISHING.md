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

## Releases

В Git хранятся исходники и маленькие текстовые manifests. Зависимости скачиваются по закреплённым хешам.
После проверки состава и происхождения в Releases можно размещать собственные GUI,
серверный JAR, SDK, agents, checksums и release notes. В архиве перечисляются исходный commit,
версия частной базы, схема протокола, что проверено и как откатиться.

Три текущих игровых ZIP нельзя автоматически превращать в публичный релиз:
они содержат игровую базу/ресурсы, право перепубликации которых этим проектом не установлено.
Git LFS решает хранение больших файлов, а не вопрос прав на их распространение.

Политики платформы проверяются перед релизом:
[крупные файлы](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github),
[Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases),
[push protection](https://docs.github.com/en/code-security/how-tos/secure-your-secrets/prevent-future-leaks/enable-push-protection).
