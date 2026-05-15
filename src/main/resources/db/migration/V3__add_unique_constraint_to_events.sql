-- Добавляем уникальное ограничение для ON CONFLICT
ALTER TABLE events ADD CONSTRAINT unique_home_away_time UNIQUE (home_team_id, away_team_id, start_time);