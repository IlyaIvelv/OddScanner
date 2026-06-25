-- Букмекеры
CREATE TABLE bookmakers (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- События (матчи)
CREATE TABLE events (
    id BIGSERIAL PRIMARY KEY,
    bookmaker_id BIGINT NOT NULL,
    external_id VARCHAR(200) NOT NULL,
    sport_name VARCHAR(100),
    league_name VARCHAR(200),
    team1 VARCHAR(200),
    team2 VARCHAR(200),
    starts_at TIMESTAMP,
    event_url VARCHAR(500),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(bookmaker_id, external_id)
);

CREATE INDEX idx_events_bookmaker ON events(bookmaker_id);
CREATE INDEX idx_events_active ON events(is_active) WHERE is_active = TRUE;

-- Рынки (типы ставок)
CREATE TABLE markets (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL,
    market_type VARCHAR(100) NOT NULL,
    market_name VARCHAR(200),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(event_id, market_type)
);

CREATE INDEX idx_markets_event ON markets(event_id);

-- Исходы (коэффициенты)
CREATE TABLE outcomes (
    id BIGSERIAL PRIMARY KEY,
    market_id BIGINT NOT NULL,
    outcome_name VARCHAR(200) NOT NULL,
    odds DECIMAL(10, 4) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_outcomes_market ON outcomes(market_id);
CREATE INDEX idx_outcomes_active ON outcomes(is_active) WHERE is_active = TRUE;

-- Вилки (арбитражные возможности)
CREATE TABLE arb_opportunities (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL,
    market_type VARCHAR(100) NOT NULL,
    profit_percent DECIMAL(10, 4) NOT NULL,
    status VARCHAR(50) DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_arb_event ON arb_opportunities(event_id);
CREATE INDEX idx_arb_status ON arb_opportunities(status);

-- Ножки вилки
CREATE TABLE arb_leg (
    id BIGSERIAL PRIMARY KEY,
    arb_id BIGINT NOT NULL,
    bookmaker_id BIGINT NOT NULL,
    outcome_id BIGINT NOT NULL,
    odds DECIMAL(10, 4) NOT NULL,
    stake_percent DECIMAL(10, 4),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_arb_leg_arb ON arb_leg(arb_id);

-- Начальные данные
INSERT INTO bookmakers (code, name) VALUES
    ('FONBET', 'Фонбет'),
    ('BET365', 'Bet365'),
    ('MARATHON', 'Марафон');