// File: src/main/java/com/oddscanner/normalization/LeagueMatcher.java

package com.oddscanner.normalization;

import com.oddscanner.generated.tables.records.LeaguesRecord;
import java.util.Optional;

public interface LeagueMatcher {
    Optional<LeaguesRecord> findCanonicalLeague(String normalizedName, Long sportId); // sportId для уточнения (лига "Premier League" в футболе != "Premier League" в теннисе)
    LeaguesRecord createCanonicalLeague(String canonicalName, Long sportId);
    String normalizeLeagueName(String rawName);
}