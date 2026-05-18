-- Маппинг типов рынков между букмекерами
INSERT INTO outcome_key_mappings (bookmaker_code, original_key, normalized_key, market_type) VALUES
-- Fonbet -> нормализованный
('fonbet', '1', 'HOME', 'ONE_X_TWO'),
('fonbet', 'X', 'DRAW', 'ONE_X_TWO'),
('fonbet', '2', 'AWAY', 'ONE_X_TWO'),

-- Marathon -> нормализованный
('marathon', 'HOME_WIN', 'HOME', 'ONE_X_TWO'),
('marathon', 'DRAW', 'DRAW', 'ONE_X_TWO'),
('marathon', 'AWAY_WIN', 'AWAY', 'ONE_X_TWO')
    ON CONFLICT (bookmaker_code, original_key, market_type) DO NOTHING;