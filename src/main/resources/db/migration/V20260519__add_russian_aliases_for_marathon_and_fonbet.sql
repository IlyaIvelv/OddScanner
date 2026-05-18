-- =====================================================
-- Миграция: Добавление русских алиасов для Marathon и Fonbet
-- Дата: 2026-05-18
-- Описание: Добавляет русскоязычные алиасы команд и обновляет canonical_team_id
-- =====================================================

-- =====================================================
-- 1. Добавляем русские алиасы для Marathon
-- =====================================================
INSERT INTO team_aliases (alias, bookmaker_code, canonical_team_id) VALUES
                                                                        ('ПСЖ', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Paris Saint-Germain')),
                                                                        ('Арсенал', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Arsenal')),
                                                                        ('Челси', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Chelsea')),
                                                                        ('Манчестер Сити', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Manchester City')),
                                                                        ('Манчестер Юнайтед', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Manchester United')),
                                                                        ('Тоттенхэм', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Tottenham Hotspur')),
                                                                        ('Тоттенхэм Хотспур', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Tottenham Hotspur')),
                                                                        ('Барселона', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Barcelona')),
                                                                        ('Реал Мадрид', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Real Madrid')),
                                                                        ('Атлетико Мадрид', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Atletico Madrid')),
                                                                        ('Бавария', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Bayern Munich')),
                                                                        ('Бавария Мюнхен', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Bayern Munich')),
                                                                        ('Ливерпуль', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Liverpool')),
                                                                        ('Ювентус', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Juventus')),
                                                                        ('Интер', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Inter Milan')),
                                                                        ('Милан', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'AC Milan')),
                                                                        ('Рома', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Roma')),
                                                                        ('Наполи', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Napoli')),
                                                                        ('Борнмут', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Bournemouth')),
                                                                        ('Бернли', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Burnley')),
                                                                        ('Ньюкасл', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Newcastle United')),
                                                                        ('Вест Хэм', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'West Ham United')),
                                                                        ('Эвертон', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Everton')),
                                                                        ('Фулхэм', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Fulham')),
                                                                        ('Спартак Москва', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Spartak Moscow')),
                                                                        ('Спартак', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Spartak Moscow')),
                                                                        ('Зенит', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Zenit')),
                                                                        ('Краснодар', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Krasnodar')),
                                                                        ('Локомотив', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Lokomotiv Moscow')),
                                                                        ('ЦСКА', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'CSKA Moscow')),
                                                                        ('Рубин', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Rubin Kazan')),
                                                                        ('Динамо Москва', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Dynamo Moscow'))
    ON CONFLICT (alias, bookmaker_code) DO NOTHING;

-- =====================================================
-- 2. Добавляем недостающие алиасы для Fonbet
-- =====================================================
INSERT INTO team_aliases (alias, bookmaker_code, canonical_team_id) VALUES
                                                                        ('ПСЖ', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Paris Saint-Germain')),
                                                                        ('Манчестер Сити', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Manchester City')),
                                                                        ('Манчестер Юнайтед', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Manchester United')),
                                                                        ('Ливерпуль', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Liverpool')),
                                                                        ('Бавария', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Bayern Munich')),
                                                                        ('Бавария Мюнхен', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Bayern Munich')),
                                                                        ('Боруссия Дортмунд', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Borussia Dortmund')),
                                                                        ('Ювентус', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Juventus')),
                                                                        ('Интер', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Inter Milan')),
                                                                        ('Милан', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'AC Milan'))
    ON CONFLICT (alias, bookmaker_code) DO NOTHING;

-- =====================================================
-- 3. Обновляем canonical_team_id для существующих событий Marathon
-- =====================================================
UPDATE events e
SET canonical_home_team_id = ta.canonical_team_id
    FROM team_aliases ta
WHERE e.bookmaker_code = 'marathon'
  AND ta.bookmaker_code = 'marathon'
  AND e.home_team = ta.alias
  AND e.canonical_home_team_id IS NULL;

UPDATE events e
SET canonical_away_team_id = ta.canonical_team_id
    FROM team_aliases ta
WHERE e.bookmaker_code = 'marathon'
  AND ta.bookmaker_code = 'marathon'
  AND e.away_team = ta.alias
  AND e.canonical_away_team_id IS NULL;

-- =====================================================
-- 4. Обновляем canonical_team_id для существующих событий Fonbet
-- =====================================================
UPDATE events e
SET canonical_home_team_id = ta.canonical_team_id
    FROM team_aliases ta
WHERE e.bookmaker_code = 'fonbet'
  AND ta.bookmaker_code = 'fonbet'
  AND e.home_team = ta.alias
  AND e.canonical_home_team_id IS NULL;

UPDATE events e
SET canonical_away_team_id = ta.canonical_team_id
    FROM team_aliases ta
WHERE e.bookmaker_code = 'fonbet'
  AND ta.bookmaker_code = 'fonbet'
  AND e.away_team = ta.alias
  AND e.canonical_away_team_id IS NULL;

-- =====================================================
-- 5. Проверочные запросы (закомментированы, можно раскомментировать для проверки)
-- =====================================================
-- SELECT
--     bookmaker_code,
--     COUNT(*) as total,
--     COUNT(canonical_home_team_id) as with_home_team,
--     COUNT(canonical_away_team_id) as with_away_team
-- FROM events
-- WHERE start_time > NOW() - INTERVAL '7 days'
-- GROUP BY bookmaker_code;

-- SELECT
--     e.id,
--     e.bookmaker_code,
--     e.home_team,
--     e.away_team,
--     ct.name as canonical_home,
--     ct2.name as canonical_away
-- FROM events e
-- LEFT JOIN canonical_teams ct ON e.canonical_home_team_id = ct.id
-- LEFT JOIN canonical_teams ct2 ON e.canonical_away_team_id = ct2.id
-- WHERE e.bookmaker_code = 'marathon'
--   AND (e.canonical_home_team_id IS NOT NULL OR e.canonical_away_team_id IS NOT NULL)
-- LIMIT 20;

-- =====================================================
-- 6. Создаём индексы для ускорения матчинга (если ещё нет)
-- =====================================================
CREATE INDEX IF NOT EXISTS idx_events_canonical_home_team ON events(canonical_home_team_id);
CREATE INDEX IF NOT EXISTS idx_events_canonical_away_team ON events(canonical_away_team_id);
CREATE INDEX IF NOT EXISTS idx_team_aliases_bookmaker_alias ON team_aliases(bookmaker_code, alias);