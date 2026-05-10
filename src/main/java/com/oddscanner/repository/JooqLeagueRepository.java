package com.oddscanner.repository;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.LeaguesRecord;
import com.oddscanner.generated.tables.records.SportsRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JooqLeagueRepository implements LeagueRepository {

    private final DSLContext dsl;

    public JooqLeagueRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<LeaguesRecord> findAll() {
        return dsl.selectFrom(Tables.LEAGUES).fetchInto(LeaguesRecord.class);
    }

    @Override
    public Optional<LeaguesRecord> findById(Long id) {
        LeaguesRecord record = dsl.selectFrom(Tables.LEAGUES)
                .where(Tables.LEAGUES.ID.eq(id))
                .fetchOneInto(LeaguesRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public Optional<LeaguesRecord> findByNameAndCountry(String name, String country) {
        LeaguesRecord record = dsl.selectFrom(Tables.LEAGUES)
                .where(Tables.LEAGUES.NAME.eq(name))
                .and(Tables.LEAGUES.COUNTRY.eq(country)) // Используем and() для clarity
                .fetchOneInto(LeaguesRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public LeaguesRecord save(LeaguesRecord record) {
        if (record.getId() == null) {
            // INSERT
            return dsl.insertInto(Tables.LEAGUES)
                    .set(record)
                    .returningResult(Tables.LEAGUES.fields())
                    .fetchOne()
                    .into(LeaguesRecord.class);
        } else {
            // UPDATE
            return dsl.update(Tables.LEAGUES)
                    .set(record)
                    .where(Tables.LEAGUES.ID.eq(record.getId()))
                    .returningResult(Tables.LEAGUES.fields())
                    .fetchOne()
                    .into(LeaguesRecord.class);
        }
    }
}