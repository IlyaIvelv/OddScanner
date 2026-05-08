# OddScanner

Инструмент для поиска арбитражных ставок (вилок) у российских букмекеров — Фонбет и Марафон. Поддерживает сложные рынки: 1X2, тоталы, форы, угловые, карточки, «обе забьют».

## Run & Operate

- `pnpm --filter @workspace/api-server run dev` — запустить API-сервер (порт определяется через `PORT`)
- `pnpm run typecheck` — полная проверка типов по всем пакетам
- `pnpm run build` — typecheck + сборка всех пакетов
- `pnpm --filter @workspace/db run push` — применить изменения схемы БД (только dev)
- Требуемые env: `DATABASE_URL` — строка подключения к PostgreSQL

## Stack

- pnpm workspaces, Node.js 24, TypeScript 5.9
- API: Express 5
- DB: PostgreSQL + Drizzle ORM
- Валидация: Zod, drizzle-zod
- Сборка: esbuild (ESM bundle)
- Планировщик: node-cron
- HTTP-клиент: axios

## Where things live

```
artifacts/api-server/src/
├── domain/types.ts              # Доменные типы (RawEvent, RawMarket, ArbCandidate...)
├── scrapers/
│   ├── base-scraper.ts          # Абстрактный класс BaseScraper
│   ├── fonbet-scraper.ts        # Скрапер Фонбет (REST API)
│   ├── marafon-scraper.ts       # Скрапер Марафон (REST API)
│   └── registry.ts             # Реестр скраперов — сюда добавлять новые БК
├── services/
│   ├── event-matcher.ts         # Матчинг событий между БК (нормализация, Левенштейн)
│   ├── arb-calculator.ts        # Расчёт вилок (implied probability)
│   ├── arb-finder.ts            # Оркестратор: скрейп → матчинг → расчёт → сохранение
│   └── scheduler.ts             # Планировщик на node-cron (singleton)
└── routes/
    ├── arbs.ts                  # GET /api/arbs, GET /api/arbs/:id
    ├── events.ts                # GET /api/events, GET /api/events/:id/markets
    ├── scrape.ts                # POST /api/scrape/trigger, GET /api/scrape/jobs
    └── scheduler-route.ts       # GET/PUT /api/scheduler

lib/db/src/schema/               # Drizzle-схемы
├── bookmakers.ts
├── events.ts
├── markets.ts
├── outcomes.ts
├── arbs.ts
├── scrape-jobs.ts
└── scheduler-config.ts
```

## API endpoints

| Method | Path | Описание |
|--------|------|----------|
| GET | /api/healthz | Статус сервиса |
| GET | /api/arbs | Список вилок (фильтры: active, minProfit, limit) |
| GET | /api/arbs/:id | Детали вилки |
| GET | /api/events | Список матчей |
| GET | /api/events/:id/markets | Рынки по матчу |
| POST | /api/scrape/trigger | Запустить скрейп + поиск вилок вручную |
| GET | /api/scrape/jobs | История задач скрейпинга |
| GET | /api/scrape/bookmakers | Список зарегистрированных БК |
| GET | /api/scheduler | Статус и настройки планировщика |
| PUT | /api/scheduler | Обновить настройки (cron, enabled, minProfitPct) |

## Architecture decisions

- **Реестр скраперов** (`scrapers/registry.ts`) — чтобы добавить новую БК, достаточно создать класс наследующий `BaseScraper` и зарегистрировать его в `registry.ts`.
- **EventMatcher** использует нормализацию названий команд + расстояние Левенштейна для сопоставления одинаковых матчей у разных БК.
- **ArbCalculator** перебирает лучшие коэффициенты по каждому исходу и считает вилку через implied probability (сумма 1/odds < 1).
- **Планировщик** — синглтон, конфигурация хранится в БД. По умолчанию запускается каждые 5 минут (`*/5 * * * *`).
- **Seed при старте** — букмекеры автоматически заполняются при первом запуске сервера.

## Как добавить новую БК

1. Создать `artifacts/api-server/src/scrapers/my-bk-scraper.ts` наследующий `BaseScraper`
2. Реализовать метод `fetchEvents(): Promise<RawEvent[]>`
3. Зарегистрировать в `artifacts/api-server/src/scrapers/registry.ts`
4. При старте сервера букмекер подтянется через seed автоматически

## User preferences

- Русскоязычный проект, общение на русском
- Только бэкенд/API (без фронтенда пока)
- Расширяемая архитектура (реестр скраперов)
- Старт с Фонбет + Марафон

## Gotchas

- Скраперы могут блокироваться букмекерами — может понадобиться ротация User-Agent или прокси
- `pnpm run typecheck:libs` нужно запускать после изменений схемы в `lib/db`, иначе TypeScript не увидит новые экспорты
- Не запускать `pnpm dev` в корне воркспейса — только через workflow

## Pointers

- Схема БД: `lib/db/src/schema/`
- Реестр скраперов: `artifacts/api-server/src/scrapers/registry.ts`
- Конфиг планировщика в БД: таблица `scheduler_config`
