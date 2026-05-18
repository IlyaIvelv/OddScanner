-- =====================================================
-- 1. Создаём таблицу каноничных команд
-- =====================================================
CREATE TABLE IF NOT EXISTS canonical_teams (
                                               id SERIAL PRIMARY KEY,
                                               name VARCHAR(255) UNIQUE NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
    );

-- =====================================================
-- 2. Обновляем структуру team_aliases (если нужно)
-- =====================================================
ALTER TABLE team_aliases ADD COLUMN IF NOT EXISTS bookmaker_code VARCHAR(50);
ALTER TABLE team_aliases ADD COLUMN IF NOT EXISTS canonical_team_id INTEGER REFERENCES canonical_teams(id) ON DELETE CASCADE;
ALTER TABLE team_aliases ADD COLUMN IF NOT EXISTS alias VARCHAR(255);
ALTER TABLE team_aliases ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();

-- =====================================================
-- 3. Добавляем колонки в таблицу events
-- =====================================================
ALTER TABLE events ADD COLUMN IF NOT EXISTS canonical_home_team_id INTEGER REFERENCES canonical_teams(id);
ALTER TABLE events ADD COLUMN IF NOT EXISTS canonical_away_team_id INTEGER REFERENCES canonical_teams(id);

-- =====================================================
-- 4. Заполняем каноничные команды (на основе Marathon)
-- =====================================================
INSERT INTO canonical_teams (name) VALUES
                                       ('CSKA Moscow'),
                                       ('Lokomotiv Moscow'),
                                       ('Sochi'),
                                       ('Akhmat Grozny'),
                                       ('Baltika'),
                                       ('Dynamo Moscow'),
                                       ('Rostov'),
                                       ('Zenit'),
                                       ('Dynamo Makhachkala'),
                                       ('Spartak Moscow'),
                                       ('Rubin Kazan'),
                                       ('Paris NN'),
                                       ('Krasnodar'),
                                       ('Orenburg'),
                                       ('Krylya Sovetov'),
                                       ('Akron'),
                                       ('Newcastle United'),
                                       ('West Ham United'),
                                       ('Arsenal'),
                                       ('Burnley'),
                                       ('Tottenham Hotspur'),
                                       ('Chelsea'),
                                       ('Manchester City'),
                                       ('Bournemouth'),
                                       ('Everton'),
                                       ('Fulham'),
                                       ('Leicester City'),
                                       ('Liverpool'),
                                       ('Manchester United'),
                                       ('Southampton'),
                                       ('Wolverhampton Wanderers'),
                                       ('Brighton & Hove Albion'),
                                       ('Crystal Palace'),
                                       ('Nottingham Forest'),
                                       ('Brentford'),
                                       ('Ipswich Town'),
                                       ('Leeds United'),
                                       ('Middlesbrough'),
                                       ('Norwich City'),
                                       ('Queens Park Rangers'),
                                       ('Sheffield United'),
                                       ('Stoke City'),
                                       ('Sunderland'),
                                       ('Watford'),
                                       ('West Bromwich Albion'),
                                       ('Barcelona'),
                                       ('Real Madrid'),
                                       ('Atletico Madrid'),
                                       ('Sevilla'),
                                       ('Valencia'),
                                       ('Villarreal'),
                                       ('Real Betis'),
                                       ('Athletic Bilbao'),
                                       ('Real Sociedad'),
                                       ('Getafe'),
                                       ('Osasuna'),
                                       ('Celta Vigo'),
                                       ('Alaves'),
                                       ('Espanyol'),
                                       ('Mallorca'),
                                       ('Levante'),
                                       ('Granada'),
                                       ('Cadiz'),
                                       ('Rayo Vallecano'),
                                       ('Elche'),
                                       ('Juventus'),
                                       ('Inter Milan'),
                                       ('AC Milan'),
                                       ('Roma'),
                                       ('Napoli'),
                                       ('Lazio'),
                                       ('Fiorentina'),
                                       ('Atalanta'),
                                       ('Torino'),
                                       ('Bologna'),
                                       ('Udinese'),
                                       ('Sassuolo'),
                                       ('Empoli'),
                                       ('Salernitana'),
                                       ('Lecce'),
                                       ('Cremonese'),
                                       ('Spezia'),
                                       ('Verona'),
                                       ('Sampdoria'),
                                       ('Bayern Munich'),
                                       ('Borussia Dortmund'),
                                       ('RB Leipzig'),
                                       ('Bayer Leverkusen'),
                                       ('Union Berlin'),
                                       ('Freiburg'),
                                       ('Mainz'),
                                       ('Borussia Monchengladbach'),
                                       ('Cologne'),
                                       ('Eintracht Frankfurt'),
                                       ('Augsburg'),
                                       ('Stuttgart'),
                                       ('Hoffenheim'),
                                       ('Werder Bremen'),
                                       ('Bochum'),
                                       ('Paris Saint-Germain'),
                                       ('Marseille'),
                                       ('Monaco'),
                                       ('Lyon'),
                                       ('Lille'),
                                       ('Rennes'),
                                       ('Nice'),
                                       ('Lens'),
                                       ('Strasbourg'),
                                       ('Montpellier'),
                                       ('Nantes'),
                                       ('Brest'),
                                       ('Toulouse'),
                                       ('Reims'),
                                       ('Clermont'),
                                       ('Angers'),
                                       ('Auxerre'),
                                       ('Ajaccio'),
                                       ('Troyes'),
                                       ('Porto'),
                                       ('Benfica'),
                                       ('Sporting Lisbon'),
                                       ('Braga'),
                                       ('PSV Eindhoven'),
                                       ('Ajax'),
                                       ('Feyenoord'),
                                       ('Celtic'),
                                       ('Rangers'),
                                       ('Galatasaray'),
                                       ('Fenerbahce'),
                                       ('Besiktas'),
                                       ('Trabzonspor'),
                                       ('Basel'),
                                       ('Young Boys'),
                                       ('Salzburg'),
                                       ('Shakhtar Donetsk'),
                                       ('Dynamo Kyiv')
    ON CONFLICT (name) DO NOTHING;

-- =====================================================
-- 5. Алиасы для Fonbet
-- =====================================================
INSERT INTO team_aliases (alias, bookmaker_code, canonical_team_id) VALUES
                                                                        ('ЦСКА', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'CSKA Moscow')),
                                                                        ('Локомотив Москва', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Lokomotiv Moscow')),
                                                                        ('Сочи', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Sochi')),
                                                                        ('Ахмат', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Akhmat Grozny')),
                                                                        ('Балтика', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Baltika')),
                                                                        ('Динамо Москва', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Dynamo Moscow')),
                                                                        ('Ростов', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Rostov')),
                                                                        ('Зенит', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Zenit')),
                                                                        ('Динамо Махачкала', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Dynamo Makhachkala')),
                                                                        ('Спартак', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Spartak Moscow')),
                                                                        ('Рубин', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Rubin Kazan')),
                                                                        ('Пари НН', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Paris NN')),
                                                                        ('Краснодар', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Krasnodar')),
                                                                        ('Оренбург', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Orenburg')),
                                                                        ('Крылья Советов', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Krylya Sovetov')),
                                                                        ('Акрон', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Akron')),
                                                                        ('Ньюкасл', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Newcastle United')),
                                                                        ('Вест Хэм', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'West Ham United')),
                                                                        ('Арсенал', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Arsenal')),
                                                                        ('Бернли', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Burnley')),
                                                                        ('Тоттенхэм', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Tottenham Hotspur')),
                                                                        ('Челси', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Chelsea')),
                                                                        ('Манчестер Сити', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Manchester City')),
                                                                        ('Борнмут', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Bournemouth')),
                                                                        ('Барселона', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Barcelona')),
                                                                        ('Реал Мадрид', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Real Madrid')),
                                                                        ('Атлетико Мадрид', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Atletico Madrid')),
                                                                        ('Севилья', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Sevilla')),
                                                                        ('Валенсия', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Valencia')),
                                                                        ('Ювентус', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Juventus')),
                                                                        ('Интер', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Inter Milan')),
                                                                        ('Милан', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'AC Milan')),
                                                                        ('Рома', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Roma')),
                                                                        ('Наполи', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Napoli')),
                                                                        ('Бавария', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Bayern Munich')),
                                                                        ('Боруссия Дортмунд', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Borussia Dortmund')),
                                                                        ('ПСЖ', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Paris Saint-Germain')),
                                                                        ('Марсель', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Marseille')),
                                                                        ('Лион', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Lyon')),
                                                                        ('Порту', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Porto')),
                                                                        ('Бенфика', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Benfica')),
                                                                        ('Спортинг', 'fonbet', (SELECT id FROM canonical_teams WHERE name = 'Sporting Lisbon'))
    ON CONFLICT DO NOTHING;

-- =====================================================
-- 6. Алиасы для Marathon (основные названия)
-- =====================================================
INSERT INTO team_aliases (alias, bookmaker_code, canonical_team_id) VALUES
                                                                        ('CSKA Moscow', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'CSKA Moscow')),
                                                                        ('Lokomotiv Moscow', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Lokomotiv Moscow')),
                                                                        ('Sochi', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Sochi')),
                                                                        ('Akhmat Grozny', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Akhmat Grozny')),
                                                                        ('Baltika', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Baltika')),
                                                                        ('Dynamo Moscow', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Dynamo Moscow')),
                                                                        ('Rostov', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Rostov')),
                                                                        ('Zenit', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Zenit')),
                                                                        ('Dynamo Makhachkala', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Dynamo Makhachkala')),
                                                                        ('Spartak Moscow', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Spartak Moscow')),
                                                                        ('Rubin Kazan', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Rubin Kazan')),
                                                                        ('Paris NN', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Paris NN')),
                                                                        ('Krasnodar', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Krasnodar')),
                                                                        ('Orenburg', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Orenburg')),
                                                                        ('Krylya Sovetov', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Krylya Sovetov')),
                                                                        ('Akron', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Akron')),
                                                                        ('Newcastle United', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Newcastle United')),
                                                                        ('West Ham United', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'West Ham United')),
                                                                        ('Arsenal', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Arsenal')),
                                                                        ('Burnley', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Burnley')),
                                                                        ('Tottenham Hotspur', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Tottenham Hotspur')),
                                                                        ('Chelsea', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Chelsea')),
                                                                        ('Manchester City', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Manchester City')),
                                                                        ('Bournemouth', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Bournemouth')),
                                                                        ('Barcelona', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Barcelona')),
                                                                        ('Real Madrid', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Real Madrid')),
                                                                        ('Atletico Madrid', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Atletico Madrid')),
                                                                        ('Sevilla', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Sevilla')),
                                                                        ('Valencia', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Valencia')),
                                                                        ('Juventus', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Juventus')),
                                                                        ('Inter Milan', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Inter Milan')),
                                                                        ('AC Milan', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'AC Milan')),
                                                                        ('Roma', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Roma')),
                                                                        ('Napoli', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Napoli')),
                                                                        ('Bayern Munich', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Bayern Munich')),
                                                                        ('Borussia Dortmund', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Borussia Dortmund')),
                                                                        ('Paris Saint-Germain', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Paris Saint-Germain')),
                                                                        ('Marseille', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Marseille')),
                                                                        ('Lyon', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Lyon')),
                                                                        ('Porto', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Porto')),
                                                                        ('Benfica', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Benfica')),
                                                                        ('Sporting Lisbon', 'marathon', (SELECT id FROM canonical_teams WHERE name = 'Sporting Lisbon'))
    ON CONFLICT DO NOTHING;

-- =====================================================
-- 7. Обновляем существующие события, проставляя canonical_team_id
-- =====================================================
UPDATE events
SET canonical_home_team_id = ta.canonical_team_id
    FROM team_aliases ta
WHERE events.bookmaker_code = ta.bookmaker_code
  AND events.home_team = ta.alias
  AND events.canonical_home_team_id IS NULL;

UPDATE events
SET canonical_away_team_id = ta.canonical_team_id
    FROM team_aliases ta
WHERE events.bookmaker_code = ta.bookmaker_code
  AND events.away_team = ta.alias
  AND events.canonical_away_team_id IS NULL;

-- =====================================================
-- 8. Создаём индексы для быстрого поиска
-- =====================================================
CREATE INDEX IF NOT EXISTS idx_events_canonical_home_team ON events(canonical_home_team_id);
CREATE INDEX IF NOT EXISTS idx_events_canonical_away_team ON events(canonical_away_team_id);
CREATE INDEX IF NOT EXISTS idx_events_bookmaker_code ON events(bookmaker_code);
CREATE INDEX IF NOT EXISTS idx_team_aliases_bookmaker ON team_aliases(bookmaker_code, alias);