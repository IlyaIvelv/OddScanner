-- =====================================================
-- Миграция: Добавление южноамериканских команд и исправление матчинга
-- Дата: 2026-05-19
-- Описание: Добавляет недостающие команды из южноамериканских турниров
-- =====================================================

-- =====================================================
-- 1. Добавляем недостающие команды в canonical_teams
-- =====================================================
INSERT INTO canonical_teams (name) VALUES
                                       ('Росарио Сентраль'),
                                       ('Универсидад Сентраль де Венесуэла'),
                                       ('Флуминенсе'),
                                       ('Боливар'),
                                       ('Индепендьенте дель Валье'),
                                       ('Либертад'),
                                       ('Насьональ Монтевидео'),
                                       ('Университарио'),
                                       ('Палмейрас'),
                                       ('Серро Портеньо'),
                                       ('Фламенго'),
                                       ('Эстудиантес Ла-Плата'),
                                       ('Хуниор Барранкилья'),
                                       ('Спортинг Кристаль'),
                                       ('Пеньяроль'),
                                       ('Коринтианс-СП'),
                                       ('Универсидад Католика Сантьяго'),
                                       ('Барселона Гуаякиль'),
                                       ('Бока Хуниорс'),
                                       ('Крузейро Минейро')
    ON CONFLICT (name) DO NOTHING;

-- =====================================================
-- 2. Добавляем алиасы для этих команд (для Marathon)
-- =====================================================
INSERT INTO team_aliases (alias, bookmaker_code, canonical_team_id)
SELECT
    e.home_team,
    e.bookmaker_code,
    ct.id
FROM events e
         JOIN canonical_teams ct ON e.home_team = ct.name
WHERE e.bookmaker_code = 'marathon'
  AND e.canonical_home_team_id IS NULL
    ON CONFLICT (alias, bookmaker_code) DO NOTHING;

INSERT INTO team_aliases (alias, bookmaker_code, canonical_team_id)
SELECT
    e.away_team,
    e.bookmaker_code,
    ct.id
FROM events e
         JOIN canonical_teams ct ON e.away_team = ct.name
WHERE e.bookmaker_code = 'marathon'
  AND e.canonical_away_team_id IS NULL
    ON CONFLICT (alias, bookmaker_code) DO NOTHING;

-- =====================================================
-- 3. Добавляем алиасы для Fonbet (южноамериканские команды)
-- =====================================================
INSERT INTO team_aliases (alias, bookmaker_code, canonical_team_id) VALUES
                                                                        ('Бока Хуниорс', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Бока Хуниорс')),
                                                                        ('Индепендьенте дель Валье', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Индепендьенте дель Валье')),
                                                                        ('Насьональ Монтевидео', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Насьональ Монтевидео')),
                                                                        ('Палмейрас СП', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Палмейрас')),
                                                                        ('Пеньяроль Монтевидео', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Пеньяроль')),
                                                                        ('Росарио Сентраль', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Росарио Сентраль')),
                                                                        ('Универсидад Католика', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Универсидад Католика Сантьяго')),
                                                                        ('Фламенго РЖ', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Фламенго')),
                                                                        ('Флуминенсе РЖ', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Флуминенсе')),
                                                                        ('Хуниор Барранкилья', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Хуниор Барранкилья'))
    ON CONFLICT (alias, bookmaker_code) DO NOTHING;

-- =====================================================
-- 4. Обновляем canonical_team_id для событий Marathon
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
-- 5. Обновляем canonical_team_id для событий Fonbet
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
-- 6. Проверка: сколько теперь сматченных команд
-- =====================================================
-- SELECT
--     bookmaker_code,
--     COUNT(*) as total,
--     COUNT(canonical_home_team_id) as with_home_team,
--     COUNT(canonical_away_team_id) as with_away_team
-- FROM events
-- WHERE start_time > NOW() - INTERVAL '7 days'
-- GROUP BY bookmaker_code;

-- =====================================================
-- 7. Проверка: какие команды пересекаются между БК
-- =====================================================
-- SELECT DISTINCT
--     ct.name as canonical_team,
--     COUNT(DISTINCT CASE WHEN m.canonical_home_team_id = ct.id OR m.canonical_away_team_id = ct.id THEN m.id END) as marathon_events,
--     COUNT(DISTINCT CASE WHEN f.canonical_home_team_id = ct.id OR f.canonical_away_team_id = ct.id THEN f.id END) as fonbet_events
-- FROM canonical_teams ct
-- LEFT JOIN events m ON (m.canonical_home_team_id = ct.id OR m.canonical_away_team_id = ct.id) AND m.bookmaker_code = 'marathon'
-- LEFT JOIN events f ON (f.canonical_home_team_id = ct.id OR f.canonical_away_team_id = ct.id) AND f.bookmaker_code = 'fonbet'
-- WHERE (m.id IS NOT NULL OR f.id IS NOT NULL)
--   AND ct.name NOT LIKE '%Unknown%'
-- GROUP BY ct.name
-- HAVING COUNT(DISTINCT CASE WHEN m.id IS NOT NULL THEN 1 END) > 0
--    AND COUNT(DISTINCT CASE WHEN f.id IS NOT NULL THEN 1 END) > 0
-- ORDER BY ct.name;