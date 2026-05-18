-- =====================================================
-- Миграция: Удаление дубликатов рынков и добавление уникальности
-- =====================================================

-- Удаляем дубликаты, оставляя один с наименьшим ID
DELETE FROM markets
WHERE id IN (
    SELECT id FROM (
                       SELECT id, ROW_NUMBER() OVER (
            PARTITION BY source_external_id, event_id, bookmaker_id, market_type, period, line
            ORDER BY id
        ) as rn
                       FROM markets
                   ) t
    WHERE rn > 1
);

-- Добавляем уникальное ограничение
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'unique_market_per_event'
    ) THEN
ALTER TABLE markets
    ADD CONSTRAINT unique_market_per_event
        UNIQUE (source_external_id, event_id, bookmaker_id, market_type, period, line);
END IF;
END $$;