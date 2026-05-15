-- Добавляем букмекеров, если их еще нет
INSERT INTO bookmakers (code, name, enabled, base_url, refresh_seconds)
VALUES ('marathon', 'Марафон', true, 'https://www.marathonbet.ru', 60)
    ON CONFLICT (code) DO NOTHING;

INSERT INTO bookmakers (code, name, enabled, base_url, refresh_seconds)
VALUES ('fonbet', 'Фонбет', true, 'https://www.fonbet.ru', 60)
    ON CONFLICT (code) DO NOTHING;

INSERT INTO bookmakers (code, name, enabled, base_url, refresh_seconds)
VALUES ('thesportsdb', 'TheSportsDB', true, 'https://www.thesportsdb.com', 3600)
    ON CONFLICT (code) DO NOTHING;