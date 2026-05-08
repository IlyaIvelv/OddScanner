package com.oddscanner.domain;

public enum MarketType {
    ONE_X_TWO,
    TOTAL_OVER_UNDER,
    HANDICAP,
    CORNERS_1X2,
    CORNERS_TOTAL,
    CARDS_TOTAL,
    BOTH_TEAMS_TO_SCORE,
    DOUBLE_CHANCE;

    public String toDbValue() {
        return switch (this) {
            case ONE_X_TWO -> "1X2";
            case TOTAL_OVER_UNDER -> "TOTAL_OVER_UNDER";
            case HANDICAP -> "HANDICAP";
            case CORNERS_1X2 -> "CORNERS_1X2";
            case CORNERS_TOTAL -> "CORNERS_TOTAL";
            case CARDS_TOTAL -> "CARDS_TOTAL";
            case BOTH_TEAMS_TO_SCORE -> "BOTH_TEAMS_TO_SCORE";
            case DOUBLE_CHANCE -> "DOUBLE_CHANCE";
        };
    }

    public static MarketType fromDbValue(String value) {
        return switch (value) {
            case "1X2" -> ONE_X_TWO;
            case "TOTAL_OVER_UNDER" -> TOTAL_OVER_UNDER;
            case "HANDICAP" -> HANDICAP;
            case "CORNERS_1X2" -> CORNERS_1X2;
            case "CORNERS_TOTAL" -> CORNERS_TOTAL;
            case "CARDS_TOTAL" -> CARDS_TOTAL;
            case "BOTH_TEAMS_TO_SCORE" -> BOTH_TEAMS_TO_SCORE;
            case "DOUBLE_CHANCE" -> DOUBLE_CHANCE;
            default -> throw new IllegalArgumentException("Unknown market type: " + value);
        };
    }

    public String getDisplayName() {
        return switch (this) {
            case ONE_X_TWO -> "Исход матча";
            case TOTAL_OVER_UNDER -> "Тотал";
            case HANDICAP -> "Фора";
            case CORNERS_1X2 -> "Угловые - исход";
            case CORNERS_TOTAL -> "Угловые - тотал";
            case CARDS_TOTAL -> "Карточки - тотал";
            case BOTH_TEAMS_TO_SCORE -> "Обе забьют";
            case DOUBLE_CHANCE -> "Двойной шанс";
        };
    }
}
