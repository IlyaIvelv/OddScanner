-- =====================================================
-- Инициализация схемы для OddScanner
-- =====================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 1. Букмекеры
CREATE TABLE bookmakers (
                            id BIGSERIAL PRIMARY KEY,
                            code VARCHAR(50) NOT NULL UNIQUE,
                            name VARCHAR(100) NOT NULL,
                            enabled BOOLEAN DEFAULT TRUE,
                            base_url VARCHAR(255),
                            refresh_seconds INT DEFAULT 30
);

-- 2. Виды спорта
CREATE TABLE sports (
                        id BIGSERIAL PRIMARY KEY,
                        code VARCHAR(50) NOT NULL UNIQUE,
                        name VARCHAR(100) NOT NULL
);

-- 3. Лиги
CREATE TABLE leagues (
                         id BIGSERIAL PRIMARY KEY,
                         sport_id BIGINT REFERENCES sports(id) ON DELETE CASCADE,
                         name VARCHAR(150) NOT NULL,
                         country VARCHAR(100)
);

-- 4. Команды
CREATE TABLE teams (
                       id BIGSERIAL PRIMARY KEY,
                       sport_id BIGINT REFERENCES sports(id) ON DELETE CASCADE,
                       canonical_name VARCHAR(150) NOT NULL,
                       country VARCHAR(100),
                       normalized_name VARCHAR(150)
);

-- 5. Алиасы команд
CREATE TABLE team_aliases (
                              id BIGSERIAL PRIMARY KEY,
                              team_id BIGINT REFERENCES teams(id) ON DELETE CASCADE,
                              bookmaker_id BIGINT REFERENCES bookmakers(id) ON DELETE CASCADE,
                              alias VARCHAR(150) NOT NULL,
                              UNIQUE(team_id, bookmaker_id, alias)
);

-- 6. События
CREATE TABLE events (
                        id BIGSERIAL PRIMARY KEY,
                        league_id BIGINT REFERENCES leagues(id) ON DELETE SET NULL,
                        home_team_id BIGINT REFERENCES teams(id) ON DELETE SET NULL,
                        away_team_id BIGINT REFERENCES teams(id) ON DELETE SET NULL,
                        home_team VARCHAR(150),
                        away_team VARCHAR(150),
                        start_time TIMESTAMP WITH TIME ZONE NOT NULL,
                        status VARCHAR(50) DEFAULT 'SCHEDULED',
                        bookmaker_code VARCHAR(50),
                        event_url VARCHAR(500),
                        dedupe_key VARCHAR(255) UNIQUE
);

-- 7. Внешние ссылки
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

-- 8. Рынки
CREATE TABLE markets (
                         id BIGSERIAL PRIMARY KEY,
                         event_id BIGINT REFERENCES events(id) ON DELETE CASCADE,
                         bookmaker_id BIGINT REFERENCES bookmakers(id) ON DELETE CASCADE,
                         market_type VARCHAR(50) NOT NULL,
                         period VARCHAR(20) NOT NULL,
                         line NUMERIC(10, 2),
                         source_external_id VARCHAR(100),
                         updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                         UNIQUE(event_id, bookmaker_id, market_type, period, line)
);

-- 9. Исходы
CREATE TABLE outcomes (
                          id BIGSERIAL PRIMARY KEY,
                          market_id BIGINT REFERENCES markets(id) ON DELETE CASCADE,
                          outcome_key VARCHAR(20) NOT NULL,
                          value VARCHAR(50),
                          odds NUMERIC(10, 4) NOT NULL,
                          is_active BOOLEAN DEFAULT TRUE,
                          updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                          UNIQUE(market_id, outcome_key)
);

-- 10. История коэффициентов
CREATE TABLE odds_snapshots (
                                id BIGSERIAL PRIMARY KEY,
                                outcome_id BIGINT REFERENCES outcomes(id) ON DELETE CASCADE,
                                odds NUMERIC(10, 4) NOT NULL,
                                captured_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 11. Арбитражные возможности (вилки)
CREATE TABLE arb_opportunities (
                                   id BIGSERIAL PRIMARY KEY,
                                   event_id BIGINT REFERENCES events(id) ON DELETE CASCADE,
                                   market_signature VARCHAR(255) NOT NULL,
                                   profit_pct NUMERIC(10, 6) NOT NULL,
                                   found_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                                   expired_at TIMESTAMP WITH TIME ZONE,
                                   status VARCHAR(20) DEFAULT 'ACTIVE',
                                   event_url TEXT,
                                   total_stake DECIMAL(10,2) DEFAULT 100.00,
                                   guaranteed_return DECIMAL(10,2),
                                   profit_amount DECIMAL(10,2)
);

-- 12. Ноги вилки (БЕЗ внешних ключей для совместимости с jOOQ)
CREATE TABLE arb_legs (
                          id BIGSERIAL PRIMARY KEY,
                          arb_id BIGINT NOT NULL,
                          bookmaker_id BIGINT NOT NULL,
                          market_id BIGINT NOT NULL,
                          outcome_id BIGINT NOT NULL,
                          odds NUMERIC(10, 4) NOT NULL,
                          stake_share NUMERIC(10, 6) NOT NULL,
                          stake_amount DECIMAL(10,2)
);

-- 13. Журнал парсинга
CREATE TABLE scrape_jobs (
                             id BIGSERIAL PRIMARY KEY,
                             bookmaker_id BIGINT REFERENCES bookmakers(id) ON DELETE CASCADE,
                             started_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                             finished_at TIMESTAMP WITH TIME ZONE,
                             status VARCHAR(20) DEFAULT 'RUNNING',
                             events_count INT DEFAULT 0,
                             errors_count INT DEFAULT 0,
                             message TEXT
);

-- 14. Маппинг исходов
CREATE TABLE outcome_key_mappings (
                                      id BIGSERIAL PRIMARY KEY,
                                      bookmaker_code VARCHAR(50) NOT NULL,
                                      original_key VARCHAR(50) NOT NULL,
                                      normalized_key VARCHAR(50) NOT NULL,
                                      market_type VARCHAR(50) NOT NULL DEFAULT 'ONE_X_TWO',
                                      UNIQUE(bookmaker_code, original_key, market_type)
);

-- 15. Таблица для связей между матчами
CREATE TABLE event_matches (
                               id BIGSERIAL PRIMARY KEY,
                               event_id_1 BIGINT NOT NULL,
                               event_id_2 BIGINT NOT NULL,
                               match_score DECIMAL(5,4) NOT NULL,
                               home_team_match BOOLEAN DEFAULT TRUE,
                               away_team_match BOOLEAN DEFAULT TRUE,
                               time_diff_minutes INT NOT NULL,
                               created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                               updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                               UNIQUE(event_id_1, event_id_2)
);

-- Начальные данные
INSERT INTO sports (code, name) VALUES
                                    ('football', 'Football'),
                                    ('tennis', 'Tennis'),
                                    ('basketball', 'Basketball')
    ON CONFLICT (code) DO NOTHING;

INSERT INTO bookmakers (code, name, enabled, base_url, refresh_seconds) VALUES
                                                                            ('fonbet', 'Fonbet', TRUE, 'https://www.fon.bet', 30),
                                                                            ('marathon', 'Marathon Bet', TRUE, 'https://www.marathonbet.com', 60),
                                                                            ('betcity', 'Betcity', TRUE, 'https://betcity.ru', 60),
                                                                            ('ligastavok', 'Лига Ставок', TRUE, 'https://www.ligastavok.ru', 60),
                                                                            ('winline', 'Winline', TRUE, 'https://winline.ru', 60),
                                                                            ('bet365', 'Bet365', FALSE, 'https://www.bet365.com', 60),
                                                                            ('thesportsdb', 'TheSportsDB', FALSE, 'https://www.thesportsdb.com', 3600)
    ON CONFLICT (code) DO NOTHING;

INSERT INTO teams (id, canonical_name, normalized_name, sport_id) VALUES
    (1, 'Unknown', 'unknown', 1)
    ON CONFLICT (id) DO NOTHING;