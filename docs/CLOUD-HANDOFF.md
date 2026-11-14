<!-- CURRENT-SIGNING-TRANSITION -->
> Пользователь разрешил новую постоянную подпись и переустановку. **1.10.37/code60:662 теста/CI43640361858/lint/сборки прошли; permanent key пока не создан, подписанный им APK не выдан.** Вход владельца подтверждён; настройка Secrets выполняется в отдельном trusted GitHub Actions runner, поскольку облачный канал подставляет служебную identity и даёт403 даже после входа. Новый ключ будет храниться атомарно в NOTELS_SIGNING_MATERIAL, публичный SHA256 закрепляется в Git; обычные CI никогда не генерируют замену. [Текущая настройка и проверки](signing-transition-1.10.37.md). Исторический завершённый аудит36 ниже не является новым подписанным выпуском.

<!-- END-CURRENT-SIGNING-TRANSITION -->

# Продолжение NoteLS в Codex

Актуальная передача: [CODEX-HANDOFF.md](CODEX-HANDOFF.md). Исходники1.10.18/code41, Room14;55 — список/сетка локально проверен:321 тест, lint без ошибок, сборка3m34s. [Отчёт55](notes-grid-1.10.18.md), [CI43229625347](https://github.com/grigorywork/NoteLS/actions/runs/43229625347) success,321 тест, lint и сборка4m15s; [APK55 (ZIP)](https://github.com/grigorywork/NoteLS/actions/runs/43229625347/artifacts/13951005797). Далее55а — главная/корзина/таймер до времени,55б — биометрия блокнотов,56 — русский/английский интерфейс; [план](user-improvements-plan.md). Постоянная подпись не менялась, debug NOT-UPDATE не является обновлением.

Работать только с приватным https://github.com/grigorywork/NoteLS, веткаmain. Перед изменениями получить свежий HEAD и прочитать AGENTS.md, ПЕРЕДАЧА-ПРОЕКТА.md, конецagent.md, новый отчёт и передачу. Не восстанавливать проект из старых архивов. Облачная среда теряла локальные изменения; публикация в GitHub и новый CI — источник состояния.

Сборка: JDK17, Android SDK35/Build Tools35.0.0, Gradle8.9. Команда: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain --max-workers=2 --no-daemon`.

Телефон/изолированный Android-эмулятор не подключены. Compose/Robolectric проверяет логику/SQLite/раскладку; реальные PSS/CPU/батарея и клавиатура Samsung не измерялись. Не выдавать анализ или моделирование за эти измерения.

Постоянный ключ/keystore.properties существуют только на компьютере пользователя. В облако их не просить/не загружать; новый постоянный ключ не создавать, подпись не менять. Подписанный выпуск остаётся [v1.10.1](https://github.com/grigorywork/NoteLS/releases/tag/v1.10.1), SHA256 APK529cf4481ed7c2b811411ee2f09343712e96c6aa1fcf4ab90eb753a6ae120f26. CI/debug без прежнего ключа не является обновлением установленного приложения.
