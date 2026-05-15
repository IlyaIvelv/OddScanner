-- Добавляем внешние ключи для событий
ALTER TABLE events
    ADD CONSTRAINT fk_events_home_team FOREIGN KEY (home_team_id) REFERENCES teams(id);

ALTER TABLE events
    ADD CONSTRAINT fk_events_away_team FOREIGN KEY (away_team_id) REFERENCES teams(id);