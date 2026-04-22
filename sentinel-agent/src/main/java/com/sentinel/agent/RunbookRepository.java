package com.sentinel.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RunbookRepository extends JpaRepository<Runbook, Long> {
    Optional<Runbook> findByAlertNameIgnoreCase(String alertName);
}
