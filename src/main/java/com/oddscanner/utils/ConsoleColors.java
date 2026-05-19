// ConsoleColors.java
package com.oddscanner.utils;

public class ConsoleColors {
    // Сброс цвета
    public static final String RESET = "\u001B[0m";

    // Обычные цвета
    public static final String BLACK = "\u001B[30m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String PURPLE = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";
    public static final String WHITE = "\u001B[37m";

    // Яркие цвета
    public static final String BRIGHT_RED = "\u001B[91m";
    public static final String BRIGHT_GREEN = "\u001B[92m";
    public static final String BRIGHT_YELLOW = "\u001B[93m";
    public static final String BRIGHT_BLUE = "\u001B[94m";
    public static final String BRIGHT_PURPLE = "\u001B[95m";
    public static final String BRIGHT_CYAN = "\u001B[96m";

    // Фоны
    public static final String BG_RED = "\u001B[41m";
    public static final String BG_GREEN = "\u001B[42m";
    public static final String BG_YELLOW = "\u001B[43m";
    public static final String BG_BLUE = "\u001B[44m";

    // Методы для форматирования
    public static String green(String text) {
        return GREEN + text + RESET;
    }

    public static String red(String text) {
        return RED + text + RESET;
    }

    public static String yellow(String text) {
        return YELLOW + text + RESET;
    }

    public static String cyan(String text) {
        return CYAN + text + RESET;
    }

    public static String brightGreen(String text) {
        return BRIGHT_GREEN + text + RESET;
    }

    public static String brightRed(String text) {
        return BRIGHT_RED + text + RESET;
    }

    public static String status(boolean enabled) {
        return enabled ? green("✅ ВКЛЮЧЁН") : red("❌ ВЫКЛЮЧЁН");
    }
}