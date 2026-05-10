INSERT INTO bookmakers (code, name, enabled, base_url, refresh_seconds) VALUES
                                                                            ('fonbet', 'Fonbet', TRUE, 'https://www.fon.bet', 30),
                                                                            ('marathon', 'Marathon Bet', TRUE, 'https://www.marathonbet.com', 60)
    ON CONFLICT (code) DO NOTHING;

INSERT INTO sports (code, name) VALUES
                                    ('football', 'Football'),
                                    ('tennis', 'Tennis'),
                                    ('basketball', 'Basketball')
    ON CONFLICT (code) DO NOTHING;