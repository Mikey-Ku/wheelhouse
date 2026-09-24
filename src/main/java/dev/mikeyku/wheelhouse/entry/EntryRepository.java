package dev.mikeyku.wheelhouse.entry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EntryRepository extends JpaRepository<EntryRecord, String> {

    List<EntryRecord> findByContestId(String contestId);

    Optional<EntryRecord> findByShareId(String shareId);

    List<EntryRecord> findByUserId(String userId);
}
