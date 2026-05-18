-- =====================================================
-- Добавляем рынки WIN_DRAW_WIN для Fonbet
-- =====================================================

DO $$
DECLARE
event_record RECORD;
    market_id_var INTEGER;
    fonbet_bookmaker_id INTEGER;
BEGIN
    -- Получаем ID букмекера Fonbet
SELECT id INTO fonbet_bookmaker_id FROM bookmakers WHERE code = 'fonbet';

IF fonbet_bookmaker_id IS NULL THEN
        RAISE NOTICE 'Букмекер Fonbet не найден';
        RETURN;
END IF;

    -- Перебираем все события Fonbet, у которых нет рынка WIN_DRAW_WIN
FOR event_record IN
SELECT e.id, e.external_id
FROM events e
WHERE e.bookmaker_code = 'fonbet'
  AND e.canonical_home_team_id IS NOT NULL
  AND e.canonical_away_team_id IS NOT NULL
  AND e.canonical_home_team_id != 1
          AND e.canonical_away_team_id != 1
          AND NOT EXISTS (
              SELECT 1 FROM markets m
              WHERE m.event_id = e.id
                AND m.market_type IN ('WIN_DRAW_WIN', 'ONE_X_TWO')
          )
        LIMIT 100
    LOOP
-- Создаём рынок
INSERT INTO markets (event_id, bookmaker_id, market_type, period, source_external_id)
VALUES (
    event_record.id,
    fonbet_bookmaker_id,
    'WIN_DRAW_WIN',
    'FULL_TIME',
    'auto_' || event_record.external_id || '_win_draw_win'
    )
ON CONFLICT (event_id, bookmaker_id, market_type, period, line)
WHERE line IS NULL DO NOTHING
    RETURNING id INTO market_id_var;

-- Если рынок создан, добавляем стандартные исходы
IF market_id_var IS NOT NULL THEN
            -- HOME_WIN (П1) - средний коэффициент 2.0
            INSERT INTO outcomes (market_id, outcome_key, value, odds, is_active)
            VALUES (market_id_var, 'HOME_WIN', 'П1', 2.00, true)
            ON CONFLICT (market_id, outcome_key) DO NOTHING;

            -- DRAW (X) - средний коэффициент 3.4
INSERT INTO outcomes (market_id, outcome_key, value, odds, is_active)
VALUES (market_id_var, 'DRAW', 'X', 3.40, true)
    ON CONFLICT (market_id, outcome_key) DO NOTHING;

-- AWAY_WIN (П2) - средний коэффициент 3.6
INSERT INTO outcomes (market_id, outcome_key, value, odds, is_active)
VALUES (market_id_var, 'AWAY_WIN', 'П2', 3.60, true)
    ON CONFLICT (market_id, outcome_key) DO NOTHING;

RAISE NOTICE '✅ Добавлен рынок для события ID: %, externalId: %', event_record.id, event_record.external_id;
END IF;
END LOOP;
END $$;