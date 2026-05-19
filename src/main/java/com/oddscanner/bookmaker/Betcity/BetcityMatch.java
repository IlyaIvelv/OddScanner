package com.oddscanner.bookmaker.Betcity;

public class BetcityMatch {
    private String homeTeam;
    private String awayTeam;
    private String score;
    private double homeWin;
    private double draw;
    private double awayWin;

    public BetcityMatch(String homeTeam, String awayTeam, String score, double homeWin, double draw, double awayWin) {
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.score = score;
        this.homeWin = homeWin;
        this.draw = draw;
        this.awayWin = awayWin;
    }

    public boolean hasValidOdds() {
        return homeWin > 0 || draw > 0 || awayWin > 0;
    }

    // Геттеры
    public String getHomeTeam() { return homeTeam; }
    public String getAwayTeam() { return awayTeam; }
    public String getScore() { return score; }
    public double getHomeWin() { return homeWin; }
    public double getDraw() { return draw; }
    public double getAwayWin() { return awayWin; }

    @Override
    public String toString() {
        return String.format("%s vs %s [%s]: %.2f | %.2f | %.2f",
                homeTeam, awayTeam, score, homeWin, draw, awayWin);
    }
}