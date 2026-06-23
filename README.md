# 🎨 VoidRP CPM Companion

> Серверный NeoForge мод — управление косметикой через Customizable Player Models с compositing скина.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen?logo=minecraft)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-orange)
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Requires CPM](https://img.shields.io/badge/requires-CPM_0.6%2B-blueviolet)
![License](https://img.shields.io/badge/license-proprietary-red)

---

## 🗺️ Место в экосистеме

```
  Администратор: /vc grant <player> <item>
        │
  voidrp-cpm-companion (NeoForge, сервер)
        │ CPM network protocol
        ▼
  Minecraft Client + CPM mod
        │ GET /api/v1/server/auth/player-skin/<nick>
        ▼
  minecraft-backend (скин игрока для compositing)
```

---

## ✨ Возможности

- **Система косметики** — шляпы, плащи, крылья, аксессуары и любые CPM-слоты
- **Защита загрузки** — `SkinRestrictionMixin` блокирует загрузку произвольных CPM-моделей без прав
- **Skin compositing** — серверная генерация модели: скин игрока накладывается на шаблон `.cpmproject` (поддержка 64×32, 64×64 Steve, 64×64 Alex/slim)
- **Кэш скинов** — скин загружается один раз за сессию
- **Автоопределение слотов** — парсинг имён анимаций из `.bbmodel` в косметические слоты
- **Атомарное сохранение** — `players.json` пишется через temp-файл с rename

### Префиксы слотов в `.bbmodel`

| Префикс анимации | Слот |
|---|---|
| `head`, `hat` | `head` |
| `body`, `chest`, `cape` | `body` |
| `legs`, `pants`, `leggings` | `legs` |
| `feet`, `boots` | `feet` |
| `wings`, `tail`, `accessory`, `misc` | `accessory` |

---

## 📋 Требования

| Компонент | Версия |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| **CPM (Customizable Player Models)** | **0.6+ (клиент и сервер)** |
| Java | 21 |

---

## 🚀 Сборка и установка

```bash
cd voidrp-cpm-companion
./gradlew jar
# → build/libs/voidrp_cpm-1.0.0.jar
```

1. Положить `voidrp_cpm-1.0.0.jar` в `mods/` рядом с `cpm-*.jar`
2. Запустить сервер → создадутся конфиги в `config/voidrp-cpm/`
3. Заполнить `config/voidrp-cpm/config.json` (секрет бэкенда)
4. Положить `.cpmproject` и `.bbmodel` в `config/voidrp-cpm/models/`
5. `/vc reload` в игре

---

## ⚙️ Конфигурация

**`config/voidrp-cpm/config.json`:**
```json
{
  "backend_url": "https://api.void-rp.ru",
  "game_auth_secret": "секрет",
  "skin_fetch_timeout_ms": 5000
}
```

**`config/voidrp-cpm/cosmetics.json`** — автогенерируется из `.bbmodel` при `/vc reload`.

---

## 🛠️ Команды

| Команда | Описание | Права |
|---|---|---|
| `/vc grant <player> <item>` | Выдать косметику | OP 2 |
| `/vc revoke <player> <item>` | Забрать косметику | OP 2 |
| `/vc equip <item>` | Надеть из своей коллекции | игрок |
| `/vc unequip <slot>` | Снять слот | игрок |
| `/vc list` | Список своей косметики | игрок |
| `/vc reload` | Пересканировать models/, сбросить кэш | OP 2 |

---

## 🔗 Связанные репозитории

| Репо | Связь |
|---|---|
| [minecraft-backend](https://github.com/VOIDRP-MINECRAFT/minecraft-backend) | Источник скина игрока для compositing |
| [voidrp-async-ai](https://github.com/VOIDRP-MINECRAFT/voidrp-async-ai) | `CosmeticArmorSaveAsyncMixin` — async сохранение данных |

---

<div align="center">
<a href="https://void-rp.ru">🌐 Сайт</a> ·
<a href="https://github.com/VOIDRP-MINECRAFT">🏠 Организация</a>
</div>
