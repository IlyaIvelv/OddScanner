-- Индексы для производительности
CREATE INDEX IF NOT EXISTS idx_teams_normalized_name_gin ON teams USING GIN (normalized_name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_events_start_time ON events(start_time);
CREATE INDEX IF NOT EXISTS idx_events_bookmaker_code ON events(bookmaker_code);
CREATE INDEX IF NOT EXISTS idx_markets_event_lookup ON markets(event_id, market_type, period, line);
CREATE INDEX IF NOT EXISTS idx_outcomes_market_id ON outcomes(market_id);
CREATE INDEX IF NOT EXISTS idx_arb_opportunities_found_at ON arb_opportunities(found_at DESC, status);
CREATE INDEX IF NOT EXISTS idx_arb_opportunities_profit ON arb_opportunities(profit_pct DESC);
CREATE INDEX IF NOT EXISTS idx_arb_opportunities_status ON arb_opportunities(status);
CREATE INDEX IF NOT EXISTS idx_arb_legs_arb_id ON arb_legs(arb_id);
CREATE INDEX IF NOT EXISTS idx_event_matches_score ON event_matches(match_score DESC);
CREATE INDEX IF NOT EXISTS idx_outcomes_active_odds ON outcomes(is_active, odds DESC);
CREATE INDEX IF NOT EXISTS idx_markets_bookmaker_type ON markets(bookmaker_id, market_type);
CREATE INDEX IF NOT EXISTS idx_events_home_away ON events(home_team_id, away_team_id);