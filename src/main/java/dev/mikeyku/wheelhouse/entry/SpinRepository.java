package dev.mikeyku.wheelhouse.entry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpinRepository extends JpaRepository<SpinRecord, Long> {

    List<SpinRecord> findByEntryIdOrderByAtAsc(String entryId);
}
