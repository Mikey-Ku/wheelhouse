package dev.mikeyku.wheelhouse.entry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EntryRepository extends JpaRepository<EntryRecord, String> {

    List<EntryRecord> findByContestId(String contestId);
}
