-- Добавляем колонку bookmaker_code в таблицу events
ALTER TABLE events ADD COLUMN IF NOT EXISTS bookmaker_code VARCHAR(50);

-- Создаём индекс для быстрого поиска по букмекеру
CREATE INDEX IF NOT EXISTS idx_events_bookmaker_code ON events(bookmaker_code);

-- Комментарий к колонке
COMMENT ON COLUMN events.bookmaker_code IS 'Код букмекера (fonbet, marathon и т.д.)';