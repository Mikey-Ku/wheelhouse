package dev.mikeyku.wheelhouse.account;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<SessionRecord, String> {
}
