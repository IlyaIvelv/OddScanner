package com.oddscanner.normalization;

import com.oddscanner.generated.tables.records.TeamsRecord;
import java.util.Optional;

public interface TeamMatcher {
    // Находит каноническую команду по нормальному или каноническому имени
    Optional<TeamsRecord> findCanonicalTeam(String normalizedName);

    // Находит каноническую команду по внешнему (сырому) имени и коду букмекера (через team_aliases)
    Optional<TeamsRecord> findCanonicalTeamByAliasAndBookmaker(String aliasName, String bookmakerCode);

    // Создает новую каноническую команду (если не найдена)
    TeamsRecord createCanonicalTeam(String canonicalName, Long sportId);

    // Нормализует имя команды (например, приведение к нижнему регистру, удаление лишних пробелов, сокращений)
    String normalizeTeamName(String rawName);
}