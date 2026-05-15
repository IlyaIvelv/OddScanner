-- Добавляем поля для названий команд в events (если их нет)
ALTER TABLE events ADD COLUMN IF NOT EXISTS home_team VARCHAR(150);
ALTER TABLE events ADD COLUMN IF NOT EXISTS away_team VARCHAR(150);