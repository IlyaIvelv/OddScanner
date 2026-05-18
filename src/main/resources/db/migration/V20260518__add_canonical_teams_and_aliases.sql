-- =====================================================
-- 1. Создаём таблицу каноничных команд
-- =====================================================
CREATE TABLE IF NOT EXISTS canonical_teams (
                                               id SERIAL PRIMARY KEY,
                                               name VARCHAR(255) UNIQUE NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
    );

-- =====================================================
-- 2. Добавляем колонки в team_aliases (если их нет)
-- =====================================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'team_aliases' AND column_name = 'bookmaker_code') THEN
ALTER TABLE team_aliases ADD COLUMN bookmaker_code VARCHAR(50);
END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'team_aliases' AND column_name = 'canonical_team_id') THEN
ALTER TABLE team_aliases ADD COLUMN canonical_team_id INTEGER REFERENCES canonical_teams(id);
END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'team_aliases' AND column_name = 'created_at') THEN
ALTER TABLE team_aliases ADD COLUMN created_at TIMESTAMP DEFAULT NOW();
END IF;
END $$;

-- =====================================================
-- 3. Добавляем уникальность (без IF NOT EXISTS, так как это не поддерживается)
-- =====================================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'team_aliases_unique') THEN
ALTER TABLE team_aliases ADD CONSTRAINT team_aliases_unique UNIQUE (alias, bookmaker_code);
END IF;
END $$;

-- =====================================================
-- 4. Добавляем колонки в events (если ещё нет)
-- =====================================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'events' AND column_name = 'canonical_home_team_id') THEN
ALTER TABLE events ADD COLUMN canonical_home_team_id INTEGER REFERENCES canonical_teams(id);
END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'events' AND column_name = 'canonical_away_team_id') THEN
ALTER TABLE events ADD COLUMN canonical_away_team_id INTEGER REFERENCES canonical_teams(id);
END IF;
END $$;

-- =====================================================
-- 5. Создаём индексы
-- =====================================================
CREATE INDEX IF NOT EXISTS idx_events_canonical_home_team ON events(canonical_home_team_id);
CREATE INDEX IF NOT EXISTS idx_events_canonical_away_team ON events(canonical_away_team_id);
CREATE INDEX IF NOT EXISTS idx_team_aliases_bookmaker ON team_aliases(bookmaker_code, alias);