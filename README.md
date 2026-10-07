# WorldGuardFork by hplugin

[![CodeFactor](https://www.codefactor.io/repository/github/2twofactorauth3-star/worldguard-fork-hplugin/badge)](https://www.codefactor.io/repository/github/2twofactorauth3-star/worldguard-fork-hplugin)
[![bStats](https://img.shields.io/bstats/servers/34427?label=bStats%20servers)](https://bstats.org/plugin/bukkit/WorldGuardFork/34427)

[English](#english) · [Русский](#русский)

WorldGuardFork is a region-focused WorldGuard 7.0.17 fork for Paper and Folia 1.21+.

## English

### About

This fork keeps WorldGuard's region protection, public integration API, and per-world blacklist while modernizing the plugin for current Paper servers. Legacy server fallbacks, unrelated utility commands, and obsolete compatibility code were removed.

The project focuses on configurable private regions, per-world defaults, MiniMessage output, clean configuration, and efficient region checks.

### Requirements

- Java 21
- Paper or Folia 1.21+
- WorldEdit 7.3.x

Spigot and Minecraft versions below 1.21 are intentionally unsupported.

### Main features

- Cuboid and polygonal protected regions
- Owners, members, parent regions, priorities, and flags
- Creation, redefinition, selection, removal, teleportation, loading, and saving
- Per-permission-group limits for region count and claim volume
- Optional automatic claim expansion on Y and X/Z axes
- Configurable particle visualization and selection lifetime
- Protection against removing the final owner
- Confirmation before adding an offline or unknown player
- `/rg list my` and `/rg list global` scopes
- Per-world defaults for new, existing, and global regions
- Wildcard world defaults with named-world overrides
- Configurable blocked-command categories and individual messages
- Russian and English locales
- MiniMessage with String and StringList values
- Chat, action bar, and title delivery modes
- Per-message cooldowns
- `{placeholder}` and `%placeholder%` syntax
- Paper Brigadier registration and completion
- Folia-aware scheduling
- Optimized session, query, and chunk caches
- Optimized per-world block and item blacklist with deny, allow, notify, log, tell, kick, and ban actions
- Anonymous bStats metrics under service ID `34427`

### Commands

The main command is `/region`; aliases are `/rg` and `/regions`.

| Command | Description |
| --- | --- |
| `/rg define <id> [owners...]` | Create a region from the WorldEdit selection |
| `/rg redefine <id>` | Replace region bounds |
| `/rg claim <id>` | Claim the current selection |
| `/rg select [id]` | Select a region in WorldEdit |
| `/rg info [id]` | Show interactive region information |
| `/rg list my [page]` | List the player's regions |
| `/rg list global [page]` | List all regions |
| `/rg flag <id> <flag> [value]` | Set or clear a flag |
| `/rg flags [id]` | Open the interactive flag list |
| `/rg setpriority <id> <priority>` | Change priority |
| `/rg setparent <id> [parent-id]` | Set or remove a parent |
| `/rg addowner <id> <players...>` | Add owners |
| `/rg removeowner <id> <players...>` | Remove owners |
| `/rg addmember <id> <players...>` | Add members |
| `/rg removemember <id> <players...>` | Remove members |
| `/rg remove <id>` | Remove a region |
| `/rg teleport <id>` | Teleport to a region |
| `/rg toggle-bypass [on\|off]` | Toggle region bypass |
| `/rg load [world]` | Reload region data |
| `/rg save [world]` | Save region data |
| `/wg reload` | Reload configuration and locales |

### Configuration

- `locale.yml` — active locale
- `config.yml` — global protection, particles, limits, and claim behavior
- `configWorld.yml` — per-world settings
- `regionDefaults.yml` — defaults for new, existing, and global regions
- `messages.yml` — player-facing messages
- `logs.yml` — console and diagnostic messages
- `worlds/<world>/blacklist.txt` — per-world block and item rules

On the first launch, set `locale: "en"` or `locale: "ru"` in `plugins/WorldGuard/locale.yml` and restart the server. Until then, WorldGuard remains enabled in API-compatible waiting mode, while region protection and gameplay listeners stay inactive.

`regionDefaults.yml` supports a `*` fallback and named world sections such as `spawn`. Message values accept either one string or a list of strings.

### Build

```bash
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

Artifacts are written to `build/libs`.

### Metrics

bStats starts automatically and reports only standard anonymous platform statistics. The standard server-wide bStats opt-out remains available in `plugins/bStats/config.yml`, as required by the bStats terms.

### License

WorldGuard and these modifications are distributed under LGPL-3.0-or-later. See [LICENSE.txt](LICENSE.txt). The JAR and source distribution include the license.

Upstream: [EngineHub/WorldGuard](https://github.com/EngineHub/WorldGuard)

---

## Русский

### О проекте

WorldGuardFork — переработанный WorldGuard 7.0.17 для защиты регионов на современных серверах Paper и Folia 1.21+. Публичный API интеграций и blacklist для каждого мира сохранены, а устаревшие fallback-ветки, посторонние команды и ненужный совместимый код удалены.

Основные цели проекта — удобные приваты, отдельные правила миров, современный MiniMessage, понятные конфиги и быстрые проверки регионов.

### Требования

- Java 21
- Paper или Folia 1.21+
- WorldEdit 7.3.x

Spigot и версии Minecraft ниже 1.21 намеренно не поддерживаются.

### Основные возможности

- Кубические и полигональные регионы
- Владельцы, участники, родители, приоритеты и флаги
- Создание, переопределение, выделение, удаление, телепортация, загрузка и сохранение
- Лимиты количества и объёма регионов по группам прав
- Автоматическое расширение привата по Y и при необходимости по X/Z
- Настраиваемые частицы и время жизни выделения
- Запрет удаления последнего владельца
- Подтверждение добавления офлайн-игрока
- Режимы `/rg list my` и `/rg list global`
- Отдельные стандарты новых, существующих и глобальных регионов для каждого мира
- Общие правила `*` и переопределения именованных миров
- Категории запрещённых команд с отдельными сообщениями
- Русская и английская локализации
- MiniMessage и значения String/StringList
- Вывод в чат, action bar и title
- Кулдауны отдельных сообщений
- Плейсхолдеры `{placeholder}` и `%placeholder%`
- Команды и автодополнение через Paper Brigadier
- Поддержка планировщика Folia
- Оптимизированные кэши сессий, запросов и чанков
- Оптимизированный blacklist блоков и предметов для каждого мира с действиями deny, allow, notify, log, tell, kick и ban
- Анонимная статистика bStats с ID `34427`

### Команды

Основная команда — `/region`, псевдонимы — `/rg` и `/regions`.

| Команда | Описание |
| --- | --- |
| `/rg define <id> [owners...]` | Создать регион из выделения WorldEdit |
| `/rg redefine <id>` | Заменить границы региона |
| `/rg claim <id>` | Заприватить выделение |
| `/rg select [id]` | Выделить регион в WorldEdit |
| `/rg info [id]` | Показать интерактивную информацию |
| `/rg list my [page]` | Показать регионы игрока |
| `/rg list global [page]` | Показать все регионы |
| `/rg flag <id> <flag> [value]` | Установить или удалить флаг |
| `/rg flags [id]` | Открыть интерактивный список флагов |
| `/rg setpriority <id> <priority>` | Изменить приоритет |
| `/rg setparent <id> [parent-id]` | Назначить или удалить родителя |
| `/rg addowner <id> <players...>` | Добавить владельцев |
| `/rg removeowner <id> <players...>` | Удалить владельцев |
| `/rg addmember <id> <players...>` | Добавить участников |
| `/rg removemember <id> <players...>` | Удалить участников |
| `/rg remove <id>` | Удалить регион |
| `/rg teleport <id>` | Телепортироваться в регион |
| `/rg toggle-bypass [on\|off]` | Переключить обход защиты |
| `/rg load [world]` | Перезагрузить регионы |
| `/rg save [world]` | Сохранить регионы |
| `/wg reload` | Перезагрузить конфиги и локализации |

### Конфигурация

- `locale.yml` — выбор языка
- `config.yml` — защита, частицы, лимиты и создание регионов
- `configWorld.yml` — настройки отдельных миров
- `regionDefaults.yml` — стандарты новых, существующих и глобальных регионов
- `messages.yml` — сообщения игрокам
- `logs.yml` — сообщения консоли и диагностики
- `worlds/<мир>/blacklist.txt` — правила блоков и предметов отдельного мира

При первом запуске укажите `locale: "ru"` или `locale: "en"` в `plugins/WorldGuard/locale.yml` и перезапустите сервер. До выбора языка WorldGuard остаётся включённым в совместимом с API режиме ожидания, но защита регионов и игровые слушатели не работают.

В `regionDefaults.yml` ключ `*` задаёт общий шаблон, а секции вроде `spawn` переопределяют его для конкретного мира. Сообщения поддерживают одну строку и список строк.

### Сборка

```powershell
.\gradlew.bat build
```

Готовые файлы создаются в `build/libs`.

### Метрики

bStats запускается автоматически и отправляет только стандартную анонимную статистику платформы. Обязательное глобальное отключение bStats для владельца сервера остаётся доступно в `plugins/bStats/config.yml`.

### Лицензия

WorldGuard и изменения распространяются по LGPL-3.0-or-later. См. [LICENSE.txt](LICENSE.txt). Лицензия включается в JAR и архив исходников.

Оригинальный проект: [EngineHub/WorldGuard](https://github.com/EngineHub/WorldGuard)
