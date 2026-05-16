-- Таблица для хранения связей между событиями из разных букмекеров
CREATE TABLE IF NOT EXISTS event_matches (
                                             id BIGSERIAL PRIMARY KEY,
                                             event_id_1 BIGINT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    event_id_2 BIGINT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    match_score DECIMAL(5,4) NOT NULL,
    home_team_match BOOLEAN DEFAULT TRUE,
    away_team_match BOOLEAN DEFAULT TRUE,
    time_diff_minutes INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(event_id_1, event_id_2)
    );

CREATE INDEX IF NOT EXISTS idx_event_matches_event1 ON event_matches(event_id_1);
CREATE INDEX IF NOT EXISTS idx_event_matches_event2 ON event_matches(event_id_2);
CREATE INDEX IF NOT EXISTS idx_event_matches_score ON event_matches(match_score DESC);

COMMENT ON TABLE event_matches IS 'Связи между матчами из разных БК (матчинг)';
COMMENT ON COLUMN event_matches.match_score IS '0-1, чем выше тем лучше совпадение';
COMMENT ON COLUMN event_matches.time_diff_minutes IS 'Разница во времени начала матча';

-- Таблица для маппинга названий исходов между букмекерами (исправленная)
CREATE TABLE IF NOT EXISTS outcome_key_mappings (
                                                    id BIGSERIAL PRIMARY KEY,
                                                    bookmaker_code VARCHAR(50) NOT NULL,
    original_key VARCHAR(50) NOT NULL,
    normalized_key VARCHAR(50) NOT NULL,
    market_type VARCHAR(50) NOT NULL DEFAULT 'ONE_X_TWO',  -- NOT NULL теперь!
    UNIQUE(bookmaker_code, original_key, market_type)
    );

-- Вставка с обработкой конфликтов (корректная)
INSERT INTO outcome_key_mappings (bookmaker_code, original_key, normalized_key, market_type) VALUES
                                                                                                 ('marathon', '1', 'HOME', 'ONE_X_TWO'),
                                                                                                 ('marathon', 'X', 'DRAW', 'ONE_X_TWO'),
                                                                                                 ('marathon', '2', 'AWAY', 'ONE_X_TWO'),
                                                                                                 ('marathon', 'OVER', 'OVER', 'TOTAL'),
                                                                                                 ('marathon', 'UNDER', 'UNDER', 'TOTAL'),
                                                                                                 ('marathon', 'HOME', 'HOME', 'HANDICAP'),
                                                                                                 ('marathon', 'AWAY', 'AWAY', 'HANDICAP'),
                                                                                                 ('marathon', 'HOME', 'HOME', 'DOUBLE_CHANCE'),
                                                                                                 ('marathon', 'DRAW', 'DRAW', 'DOUBLE_CHANCE'),
                                                                                                 ('marathon', 'AWAY', 'AWAY', 'DOUBLE_CHANCE'),
                                                                                                 ('fonbet', '1', 'HOME', 'ONE_X_TWO'),
                                                                                                 ('fonbet', 'X', 'DRAW', 'ONE_X_TWO'),
                                                                                                 ('fonbet', '2', 'AWAY', 'ONE_X_TWO'),
                                                                                                 ('fonbet', 'OVER', 'OVER', 'TOTAL'),
                                                                                                 ('fonbet', 'UNDER', 'UNDER', 'TOTAL'),
                                                                                                 ('fonbet', 'HOME', 'HOME', 'HANDICAP'),
                                                                                                 ('fonbet', 'AWAY', 'AWAY', 'HANDICAP'),
                                                                                                 ('fonbet', '1X', '1X', 'DOUBLE_CHANCE'),
                                                                                                 ('fonbet', 'X2', 'X2', 'DOUBLE_CHANCE'),
                                                                                                 ('fonbet', '12', '12', 'DOUBLE_CHANCE'),
                                                                                                 ('1xbet', 'П1', 'HOME', 'ONE_X_TWO'),
                                                                                                 ('1xbet', 'X', 'DRAW', 'ONE_X_TWO'),
                                                                                                 ('1xbet', 'П2', 'AWAY', 'ONE_X_TWO')
    ON CONFLICT (bookmaker_code, original_key, market_type) DO NOTHING;