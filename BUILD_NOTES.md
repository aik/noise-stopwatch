# Шумомер-секундомер — лог сборки и заметки

Дата: 2026-11-11. Машина: `ole` (ssh, home `/home/aik`, Fedora, shell fish).
Проект: `~/handsfreestopwatch`.

## Что делает приложение
Android-приложение на Kotlin. Слушает микрофон и работает как секундомер,
управляемый громкостью:
- Считает уровень звука: RMS окна ~100 мс → dBFS → шкала 0..100
  (−60 dBFS = 0, 0 dBFS = 100).
- Когда уровень превышает **порог** (ползунок 0..100) — запускается отсчёт.
- Когда шум стихает — интервал закрывается и прибавляется к сумме.
- Показывает **суммарное время** всех шумных интервалов + длительность
  **текущего** интервала, индикатор уровня и статус (Тихо / ШУМ).
- Гистерезис `quietGraceMs = 400` мс: короткие провалы тишины не рвут интервал.
  Интервал засчитывается до момента последнего громкого семпла (тишина в
  "грейс-периоде" в сумму не идёт).
- **Три таймера вибрации + выбор активного**: три числовых поля (EditText,
  inputType=number, сек), в сетке 2x2 вместе с 4-й радиокнопкой «Без лимита».
  У каждого слева крупная радиокнопка (scaleX/Y=1.5, minW/H=48dp). Активен тот,
  что выбран; его значение = `alarmSeconds`. Эксклюзивность радиокнопок сделана
  вручную (не через RadioGroup). Срабатывание — по **суммарному** времени шума:
  `totalMillis + (now - intervalStart) >= alarmSeconds*1000`, один раз за запуск
  (флаг `alarmFired`, сбрасывается в `startMonitoring()`/`resetTimer()`).
  При срабатывании — вибрация (паттерн из 3 импульсов по 400 мс,
  `hasVibrator()`). Требует `VIBRATE`. API VibratorManager (31+)/Vibrator.
  Порог срабатывания (громкость) остался ползунком — калибруется по индикатору
  уровня рядом.
- **Радиокнопка «Без лимита»**: 4-я радиокнопка (`radioNoLimit`, индекс 3).
  При ней `hasLimit()`=false — вибрации/авто-стопа нет, счёт идёт бесконечно.
- **По достижении лимита таймер НЕ останавливается**: только вибрация + вспышка
  (один раз, флаг `alarmFired`), а мониторинг продолжается и `total` растёт
  дальше (видно «перебор», напр. `00:40.0 / 00:35.0`). Остановка/сброс отсчёта
  происходит при нажатии другой радиокнопки: `selectTimer` обнуляет `totalMillis`,
  `alarmFired` и `intervalStart=now` → новый цикл (мониторинг не прерывается).
  Ручной «Stop» — как раньше; «Reset» обнуляет вручную.
- **Вспышка** `flashIndicator()`: статус-блок подсвечивается жёлтым (0xFFFFC107)
  на 1 секунду, затем фон сбрасывается (`ui.postDelayed(... , 1000)`).
- **Доли секунды**: поля `numberDecimal`, принимают "9.5" (запятая тоже — в
  `fieldValue()` заменяется на точку). Внутри всё в миллисекундах: активный
  таймер даёт `alarmMillis = (сек*1000)`, сравнение `totalMillis + live >=
  alarmMillis`. Диапазон 0.1..86400 сек. Показ без лишнего ".0" (`fmtSec`).
- **Чекбокс «Auto-start»** справа от кнопки Start. Состояние сохраняется
  (`autostart`, Boolean). Если отмечен — клик по любой радиокнопке
  (`selectTimer`) запускает мониторинг (`ensurePermissionAndStart()`), если ещё
  не запущен.
- **Показ цели в total**: `textTotal` = `mm:ss.d / mm:ss.d` (цель = активный
  таймер, формат `fmt()` — всегда с десятыми). Для «No limit» — символ «∞».
  Обновляется и в простое (из `selectTimer` и ввода). Шрифт фиксированный 32sp
  (крупнее `textCurrent` 28sp), одна строка, `gravity=center`. Авторазмер
  (`autoSizeTextType`) НЕ используем: с `wrap_content` он мерил высоту по
  меньшему кеглю и обрезал текст по вертикали. Весь экран в ScrollView —
  при крупном системном шрифте прокручивается, контролы не теряются.
- **Выбор таймера по всей ячейке**: `scaleX/scaleY` у RadioButton увеличивает
  только картинку, НЕ область нажатия → тапы по «большому» кружку промахивались
  (из-за этого выбор не срабатывал). Теперь вся ячейка (`timerCell1..3`,
  `timerCellNoLimit`) кликабельна и вызывает `selectTimer(i)`.
- **Чекбокс у 3-го таймера** (`checkAutoThird`, сохраняется как `autothird`):
  если отмечен — по завершении любого таймера (1/2/3) после авто-стопа
  запускается **обратный отсчёт 5 сек** (`startAutoSwitchCountdown`, идёт уже
  при работающем мониторинге), в статусе «Switching to timer 3 in N sec»
  (`switching_in`; `updateTexts` не затирает статус, пока `autoSwitchActive`),
  затем `selectTimer(2)` (новый цикл на 3-м таймере). Любое нажатие кнопки
  (Start/Stop, Reset, выбор радио/ячейки, переключение чекбоксов) вызывает
  `cancelAutoSwitch()` и отменяет переключение.
- **Сохранение настроек** (`SharedPreferences` "settings"): порог (`threshold`,
  Float), значения трёх полей (`sec0..sec2`, Float, секунды), выбранный таймер
  (`active`, 0..3) и `autostart` (Boolean) пишутся при изменении и
  восстанавливаются в `loadSettings()`.
- Кнопки Старт/Стоп и Сброс. Во время прослушивания экран не гаснет
  (FLAG_KEEP_SCREEN_ON). Мониторинг останавливается в onStop().

Ключевая логика — `app/src/main/java/com/example/handsfreestopwatch/MainActivity.kt`
(функции `audioLoop`, `computeLevel`, поля `totalMillis` / `intervalStart` /
`lastLoudAt` / `inNoise`).

## Параметры проекта
- Kotlin, AGP 8.6.1, Kotlin plugin 1.9.24, Gradle 8.9 (wrapper).
- compileSdk/targetSdk 34, minSdk 24.
- jvmTarget / sourceCompatibility = 17, viewBinding включён.
- Зависимости: core-ktx 1.13.1, appcompat 1.7.0, material 1.12.0,
  constraintlayout 2.1.4.
- Разрешения: `RECORD_AUDIO` (запрашивается в рантайме при первом Старте),
  `VIBRATE`.
- **Локализация**: английский — дефолт (`res/values/strings.xml`), русский —
  `res/values-ru/strings.xml`. Язык выбирается автоматически по локали телефона
  (русская → русский, остальные → английский).

## Настройка окружения на `ole` (делалось один раз)
Изначально не было ни Android SDK, ни Gradle; системный JDK урезан.

1. **cmdline-tools** → `~/Android/Sdk/cmdline-tools/latest`
   (commandlinetools-linux-11076708).
2. **SDK-пакеты** через sdkmanager (+ приняты лицензии):
   `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`.
   → `~/Android/Sdk`. Лог: `~/sdk_install.log`.
3. **Gradle 8.9** скачан в `~/gradle-8.9`, им сгенерирован wrapper проекта
   (`gradlew` + `gradle/wrapper/gradle-wrapper.jar`).
4. **local.properties**: `sdk.dir=/home/aik/Android/Sdk`.

### Проблема, которую пришлось решить (jlink)
Первая сборка упала на `:app:compileDebugJavaWithJavac`:
```
Could not resolve all files for configuration ':app:androidJdkImage'.
  > Failed to transform core-for-system-modules.jar ...
    > jlink executable /usr/lib/jvm/java-21-openjdk/bin/jlink does not exist.
```
Причина: системный `java-21-openjdk` (и `java-17-openjdk`) — урезанные сборки
без модуля `jdk.jlink` и без `jmods`; devel-пакета нет, sudo не использовали.

Решение: скачан полноценный **Temurin JDK 21** в `~/jdk21` и указан Gradle:
в `gradle.properties` добавлено
```
org.gradle.java.home=/home/aik/jdk21
```
После этого `./gradlew clean assembleDebug` → BUILD SUCCESSFUL.

## Как собрать
Debug (большой, ~6.8 МБ, для отладки):
```
cd ~/handsfreestopwatch
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Release (маленький, ~1.66 МБ — рекомендуется ставить на телефон):
```
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

## Уменьшение размера APK (сделано)
Release-сборка оптимизирована:
- `isMinifyEnabled = true` (R8 выкидывает неиспользуемый код androidx/Material).
- `isShrinkResources = true` (удаляет неиспользуемые ресурсы).
- Убрана неиспользуемая зависимость `constraintlayout`.
- Release подписан debug-ключом (`signingConfig = signingConfigs.debug`),
  поэтому ставится на устройство без отдельного keystore.
Итог: 6.83 МБ (debug) → 1.66 МБ (release), ~−76%.

Дальше можно ужать ещё чуть-чуть (опционально): `resConfigs("ru","xxhdpi")`
в defaultConfig, либо собирать App Bundle (.aab) для Google Play — но для
прямой установки одним APK на Pixel 6 текущих 1.66 МБ достаточно.

Логи сборок: `~/build.log`/`~/build2.log` (настройка JDK), `~/build3.log`
(вибрация), `~/build4.log` (release).

## Как поставить на телефон
Устройство к `ole` ещё не подключали. Когда подключите по USB (с включённой
отладкой по USB):
```
adb devices
adb install -r ~/handsfreestopwatch/app/build/outputs/apk/debug/app-debug.apk
```

## Ограничения / возможные доработки
- Соберён и проверен только `assembleDebug`; на устройстве **не запускался**,
  реальная работа микрофона в UI не проверена.
- Мониторинг работает только пока приложение открыто (нет фонового сервиса /
  уведомления). Для подсчёта при свёрнутом приложении нужен foreground service.
- Порог задаётся вручную ползунком; автокалибровки фона нет.
- Иконка — системная заглушка (`@android:drawable/ic_lock_silent_mode_off`).
