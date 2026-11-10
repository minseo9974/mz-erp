package com.mzerp.journal;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    @EntityGraph(attributePaths = "lines")
    Optional<JournalEntry> findByIdAndCompanyId(Long id, Long companyId);

    @EntityGraph(attributePaths = "lines")
    List<JournalEntry> findByCompanyIdAndEntryDateBetweenOrderByEntryDateAscEntryNoAsc(
            Long companyId, LocalDate from, LocalDate to);

    List<JournalEntry> findByReversalOfIdIn(Collection<Long> reversalOfIds);

    boolean existsByReversalOfId(Long reversalOfId);
}
