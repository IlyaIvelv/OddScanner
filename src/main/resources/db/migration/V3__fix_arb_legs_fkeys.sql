-- Удаляем старые constraint'ы, если есть
ALTER TABLE arb_legs DROP CONSTRAINT IF EXISTS arb_legs_opportunity_id_fkey;
ALTER TABLE arb_legs DROP CONSTRAINT IF EXISTS arb_legs_outcome_id_fkey;
ALTER TABLE arb_legs DROP CONSTRAINT IF EXISTS fk_arb_legs_arb;
ALTER TABLE arb_legs DROP CONSTRAINT IF EXISTS fk_arb_legs_outcome;

-- Пересоздаём правильно
ALTER TABLE arb_legs
    ADD CONSTRAINT fk_arb_legs_arb
        FOREIGN KEY (arb_id) REFERENCES arb_opportunities(id) ON DELETE CASCADE;

ALTER TABLE arb_legs
    ADD CONSTRAINT fk_arb_legs_outcome
        FOREIGN KEY (outcome_id) REFERENCES outcomes(id);

-- Создаём индексы для производительности
CREATE INDEX IF NOT EXISTS idx_arb_legs_arb_id ON arb_legs(arb_id);
CREATE INDEX IF NOT EXISTS idx_arb_legs_outcome_id ON arb_legs(outcome_id);