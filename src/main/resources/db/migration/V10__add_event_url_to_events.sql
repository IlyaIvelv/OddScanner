-- Добавляем колонку для URL события
ALTER TABLE events ADD COLUMN IF NOT EXISTS event_url VARCHAR(500);

-- Обновляем существующие события (для Марафона)
UPDATE events
SET event_url = 'https://www.marathonbet.ru/su/betting/Football/' || id
WHERE event_url IS NULL AND id IS NOT NULL;