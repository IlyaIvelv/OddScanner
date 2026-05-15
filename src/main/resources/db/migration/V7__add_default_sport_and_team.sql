-- Добавляем вид спорта "Football", если его нет
INSERT INTO sports (id, code, name)
VALUES (1, 'football', 'Football')
    ON CONFLICT (id) DO NOTHING;

-- Добавляем команду "Unknown" для случаев, когда не удалось распарсить название
INSERT INTO teams (id, canonical_name, normalized_name, sport_id)
VALUES (1, 'Unknown', 'unknown', 1)
    ON CONFLICT (id) DO NOTHING;