// File: src/main/java/com/oddscanner/normalization/SportMatcher.java

package com.oddscanner.normalization;

import com.oddscanner.generated.tables.records.SportsRecord;
import java.util.Optional;

public interface SportMatcher {
    Optional<SportsRecord> findCanonicalSportByCode(String code); // Используем code, так как он уникален
    Optional<SportsRecord> findCanonicalSportByName(String normalizedName); // Или по нормальному имени
    // createCanonicalSport не нужен, так как виды спорта редко добавляются динамически
    String normalizeSportName(String rawName);
}