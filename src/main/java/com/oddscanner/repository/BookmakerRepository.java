package com.oddscanner.repository;

import com.oddscanner.generated.tables.records.BookmakersRecord;
import java.util.List;
import java.util.Optional;

public interface BookmakerRepository {
    List<BookmakersRecord> findAllEnabled();
    Optional<BookmakersRecord> findByCode(String code);
    Optional<BookmakersRecord> findById(Long id);
    List<BookmakersRecord> findAllBookmakers();
    BookmakersRecord save(BookmakersRecord record);
}