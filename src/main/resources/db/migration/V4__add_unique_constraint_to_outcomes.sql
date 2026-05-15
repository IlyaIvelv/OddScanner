-- Добавляем уникальное ограничение для ON CONFLICT в outcomes
ALTER TABLE outcomes ADD CONSTRAINT unique_market_outcome UNIQUE (market_id, outcome_key);