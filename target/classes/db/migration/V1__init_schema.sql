-- Включаем расширение для fuzzy search по названиям команд
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Таблица букмекеров
CREATE TABLE bookmakers (
                            id BIGSERIAL PRIMARY KEY,
                            code VARCHAR(50) NOT NULL UNIQUE, -- fonbet, marathon
                            name VARCHAR(100) NOT NULL,
                            enabled BOOLEAN DEFAULT TRUE,
                            base_url VARCHAR(255),
                            refresh_seconds INT DEFAULT 30
);

-- Таблица видов спорта
CREATE TABLE sports (
                        id BIGSERIAL PRIMARY KEY,
                        code VARCHAR(50) NOT NULL UNIQUE, -- football, tennis
                        name VARCHAR(100) NOT NULL
);

-- Таблица лиг
CREATE TABLE leagues (
                         id BIGSERIAL PRIMARY KEY,
                         sport_id BIGINT REFERENCES sports(id) ON DELETE CASCADE,
                         name VARCHAR(150) NOT NULL,
                         country VARCHAR(100)
);

-- Таблица команд (канонические названия)
CREATE TABLE teams (
                       id BIGSERIAL PRIMARY KEY,
                       sport_id BIGINT REFERENCES sports(id) ON DELETE CASCADE,
                       canonical_name VARCHAR(150) NOT NULL, -- Официальное название
                       country VARCHAR(100),
                       normalized_name VARCHAR(150) -- Для быстрого поиска (lowercase, без мусора)
);

-- Индекс для fuzzy поиска по нормализованному имени
CREATE INDEX idx_teams_normalized_name_gin ON teams USING GIN (normalized_name gin_trgm_ops);

-- Алиасы команд (как их называют конкретные БК)
CREATE TABLE team_aliases (
                              id BIGSERIAL PRIMARY KEY,
                              team_id BIGINT REFERENCES teams(id) ON DELETE CASCADE,
                              bookmaker_id BIGINT REFERENCES bookmakers(id) ON DELETE CASCADE,
                              alias VARCHAR(150) NOT NULL,
                              UNIQUE(team_id, bookmaker_id, alias)
);

-- События (матчи)
CREATE TABLE events (
                        id BIGSERIAL PRIMARY KEY,
                        league_id BIGINT REFERENCES leagues(id) ON DELETE CASCADE,
                        home_team_id BIGINT REFERENCES teams(id) ON DELETE CASCADE,
                        away_team_id BIGINT REFERENCES teams(id) ON DELETE CASCADE,
                        start_time TIMESTAMP WITH TIME ZONE NOT NULL,
                        status VARCHAR(50) DEFAULT 'SCHEDULED', -- SCHEDULED, LIVE, FINISHED
                        dedupe_key VARCHAR(255) UNIQUE -- Для предотвращения дубликатов при парсинге
);

-- Внешние ссылки на события (сырые данные от БК до маппинга)
CREATE TABLE event_external_refs (
                                     id BIGSERIAL PRIMARY KEY,
                                     event_id BIGINT REFERENCES events(id) ON DELETE SET NULL,
                                     bookmaker_id BIGINT REFERENCES bookmakers(id) ON DELETE CASCADE,
                                     external_id VARCHAR(100) NOT NULL,
                                     raw_home VARCHAR(150),
                                     raw_away VARCHAR(150),
                                     raw_start_time TIMESTAMP WITH TIME ZONE,
                                     last_seen_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                                     UNIQUE(bookmaker_id, external_id)
);

-- Рынки (типы ставок)
CREATE TABLE markets (
                         id BIGSERIAL PRIMARY KEY,
                         event_id BIGINT REFERENCES events(id) ON DELETE CASCADE,
                         bookmaker_id BIGINT REFERENCES bookmakers(id) ON DELETE CASCADE,
                         market_type VARCHAR(50) NOT NULL, -- ONE_X_TWO, TOTAL, HANDICAP
                         period VARCHAR(20) NOT NULL, -- FULL_TIME, HALF_1
                         line NUMERIC(10, 2), -- Например, 2.5 для тотала
                         source_external_id VARCHAR(100), -- ID рынка у БК
                         updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                         UNIQUE(event_id, bookmaker_id, market_type, period, line)
);

-- Исходы рынков
CREATE TABLE outcomes (
                          id BIGSERIAL PRIMARY KEY,
                          market_id BIGINT REFERENCES markets(id) ON DELETE CASCADE,
                          outcome_key VARCHAR(20) NOT NULL, -- HOME, AWAY, DRAW, OVER, UNDER
                          value VARCHAR(50), -- Значение исхода (например, "1", "X", "2")
                          odds NUMERIC(10, 4) NOT NULL,
                          is_active BOOLEAN DEFAULT TRUE,
                          updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- История коэффициентов (снапшоты)
CREATE TABLE odds_snapshots (
                                id BIGSERIAL PRIMARY KEY,
                                outcome_id BIGINT REFERENCES outcomes(id) ON DELETE CASCADE,
                                odds NUMERIC(10, 4) NOT NULL,
                                captured_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Вилки (арбитражные возможности)
CREATE TABLE arb_opportunities (
                                   id BIGSERIAL PRIMARY KEY,
                                   event_id BIGINT REFERENCES events(id) ON DELETE CASCADE,
                                   market_signature VARCHAR(255) NOT NULL, -- Уникальный ключ группы исходов
                                   profit_pct NUMERIC(5, 2) NOT NULL, -- Процент прибыли
                                   found_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                                   expired_at TIMESTAMP WITH TIME ZONE,
                                   status VARCHAR(20) DEFAULT 'ACTIVE' -- ACTIVE, EXPIRED
);

-- Ноги вилки (конкретные ставки)
CREATE TABLE arb_legs (
                          id BIGSERIAL PRIMARY KEY,
                          arb_id BIGINT REFERENCES arb_opportunities(id) ON DELETE CASCADE,
                          bookmaker_id BIGINT REFERENCES bookmakers(id) ON DELETE CASCADE,
                          market_id BIGINT REFERENCES markets(id) ON DELETE CASCADE,
                          outcome_id BIGINT REFERENCES outcomes(id) ON DELETE CASCADE,
                          odds NUMERIC(10, 4) NOT NULL,
                          stake_share NUMERIC(5, 4) NOT NULL -- Доля ставки в процентах
);

-- Журнал парсинга
CREATE TABLE scrape_jobs (
                             id BIGSERIAL PRIMARY KEY,
                             bookmaker_id BIGINT REFERENCES bookmakers(id) ON DELETE CASCADE,
                             started_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                             finished_at TIMESTAMP WITH TIME ZONE,
                             status VARCHAR(20) DEFAULT 'RUNNING', -- RUNNING, SUCCESS, FAILED
                             events_count INT DEFAULT 0,
                             errors_count INT DEFAULT 0,
                             message TEXT
);

-- Индексы для производительности
CREATE INDEX idx_events_start_time ON events(start_time);
CREATE INDEX idx_markets_event_lookup ON markets(event_id, market_type, period, line);
CREATE INDEX idx_outcomes_market_id ON outcomes(market_id);
CREATE INDEX idx_arb_opportunities_found_at ON arb_opportunities(found_at DESC, status);
CREATE INDEX idx_event_external_refs_lookup ON event_external_refs(bookmaker_id, external_id);