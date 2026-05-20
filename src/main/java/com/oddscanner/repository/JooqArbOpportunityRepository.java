// File: src/main/java/com/oddscanner/repository/JooqArbOpportunityRepository.java
package com.oddscanner.repository;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.ArbOpportunitiesRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class JooqArbOpportunityRepository implements ArbOpportunityRepository {
    private final DSLContext dsl;

    public JooqArbOpportunityRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<ArbOpportunitiesRecord> findAll() {
        return dsl.selectFrom(Tables.ARB_OPPORTUNITIES).fetchInto(ArbOpportunitiesRecord.class);
    }

    @Override
    public Optional<ArbOpportunitiesRecord> findById(Long id) {
        ArbOpportunitiesRecord record = dsl.selectFrom(Tables.ARB_OPPORTUNITIES)
                .where(Tables.ARB_OPPORTUNITIES.ID.eq(id))
                .fetchOneInto(ArbOpportunitiesRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public Optional<ArbOpportunitiesRecord> findByEventIdAndMarketSignature(Long eventId, String marketSignature) {
        ArbOpportunitiesRecord record = dsl.selectFrom(Tables.ARB_OPPORTUNITIES)
                .where(Tables.ARB_OPPORTUNITIES.EVENT_ID.eq(eventId))
                .and(Tables.ARB_OPPORTUNITIES.MARKET_SIGNATURE.eq(marketSignature))
                .and(Tables.ARB_OPPORTUNITIES.STATUS.eq("ACTIVE"))
                .fetchOneInto(ArbOpportunitiesRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public ArbOpportunitiesRecord save(ArbOpportunitiesRecord record) {
        if (record.getId() == null) {
            // INSERT
            return dsl.insertInto(Tables.ARB_OPPORTUNITIES)
                    .set(record)
                    .returningResult(Tables.ARB_OPPORTUNITIES.fields())
                    .fetchOne()
                    .into(ArbOpportunitiesRecord.class);
        } else {
            // UPDATE
            return dsl.update(Tables.ARB_OPPORTUNITIES)
                    .set(record)
                    .where(Tables.ARB_OPPORTUNITIES.ID.eq(record.getId()))
                    .returningResult(Tables.ARB_OPPORTUNITIES.fields())
                    .fetchOne()
                    .into(ArbOpportunitiesRecord.class);
        }
    }

    @Override
    public List<ArbOpportunitiesRecord> findActiveWithEventDetails() {
        return dsl.select(Tables.ARB_OPPORTUNITIES.fields())
                .select(Tables.EVENTS.HOME_TEAM, Tables.EVENTS.AWAY_TEAM, Tables.EVENTS.EVENT_URL, Tables.EVENTS.START_TIME)
                .from(Tables.ARB_OPPORTUNITIES)
                .join(Tables.EVENTS).on(Tables.ARB_OPPORTUNITIES.EVENT_ID.eq(Tables.EVENTS.ID))
                .where(Tables.ARB_OPPORTUNITIES.STATUS.eq("ACTIVE"))
                .orderBy(Tables.ARB_OPPORTUNITIES.PROFIT_PCT.desc())
                .fetchInto(ArbOpportunitiesRecord.class);
    }

    @Override
    public void updateStatus(Long id, String status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        dsl.update(Tables.ARB_OPPORTUNITIES)
                .set(Tables.ARB_OPPORTUNITIES.STATUS, status)
                .set(Tables.ARB_OPPORTUNITIES.EXPIRED_AT, now)
                .where(Tables.ARB_OPPORTUNITIES.ID.eq(id))
                .execute();
    }

    @Override
    public List<ArbOpportunitiesRecord> findAllSortedByProfitDesc() {
        return dsl.selectFrom(Tables.ARB_OPPORTUNITIES)
                .orderBy(Tables.ARB_OPPORTUNITIES.PROFIT_PCT.desc())
                .fetchInto(ArbOpportunitiesRecord.class);
    }
}