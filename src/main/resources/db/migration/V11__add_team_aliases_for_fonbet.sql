-- Добавляем все алиасы для команд Фонбета
INSERT INTO team_aliases (team_id, bookmaker_id, alias) VALUES

-- Первая партия
((SELECT id FROM teams WHERE canonical_name = 'Байер 04'), 4, 'Байер Леверкузен'),
((SELECT id FROM teams WHERE canonical_name = 'Вердер Бремен'), 4, 'Вердер'),
((SELECT id FROM teams WHERE canonical_name = 'Вест Хэм Юнайтед'), 4, 'Вест Хэм'),
((SELECT id FROM teams WHERE canonical_name = 'Зенит Санкт-Петербург'), 4, 'Зенит'),
((SELECT id FROM teams WHERE canonical_name = 'Локомотив Москва'), 4, 'Локомотив'),
((SELECT id FROM teams WHERE canonical_name = 'Спартак Москва'), 4, 'Спартак'),
((SELECT id FROM teams WHERE canonical_name = 'РБ Лейпциг'), 4, 'Лейпциг'),
((SELECT id FROM teams WHERE canonical_name = 'Брайтон энд Хоув Альбион'), 4, 'Брайтон'),

-- Вторая партия
((SELECT id FROM teams WHERE canonical_name = 'Рубин'), 4, 'Рубин'),
((SELECT id FROM teams WHERE canonical_name = 'Сочи'), 4, 'Сочи'),
((SELECT id FROM teams WHERE canonical_name = 'ЦСКА'), 4, 'ЦСКА Москва'),
((SELECT id FROM teams WHERE canonical_name = 'Сандерленд'), 4, 'Сандерленд'),
((SELECT id FROM teams WHERE canonical_name = 'Тоттенхэм'), 4, 'Тоттенхэм Хотспур'),
((SELECT id FROM teams WHERE canonical_name = 'Фулхэм'), 4, 'Фулхэм'),
((SELECT id FROM teams WHERE canonical_name = 'Челси'), 4, 'Челси'),
((SELECT id FROM teams WHERE canonical_name = 'Эвертон'), 4, 'Эвертон'),
((SELECT id FROM teams WHERE canonical_name = 'Санкт-Паули'), 4, 'Санкт-Паули'),
((SELECT id FROM teams WHERE canonical_name = 'Унион Берлин'), 4, 'Унион Берлин'),
((SELECT id FROM teams WHERE canonical_name = 'Фрайбург'), 4, 'Фрайбург'),
((SELECT id FROM teams WHERE canonical_name = 'Хайденхайм'), 4, 'Хайденхайм'),
((SELECT id FROM teams WHERE canonical_name = 'Хоффенхайм'), 4, 'Хоффенхайм'),
((SELECT id FROM teams WHERE canonical_name = 'Штутгарт'), 4, 'Штутгарт'),
((SELECT id FROM teams WHERE canonical_name = 'Сельта'), 4, 'Сельта')

    ON CONFLICT (team_id, bookmaker_id, alias) DO NOTHING;