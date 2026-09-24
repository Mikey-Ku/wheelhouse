package dev.mikeyku.wheelhouse.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserRecord, String> {

    Optional<UserRecord> findByNameKey(String nameKey);
}
