package com.nexus360.runbook;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RunbookRepository extends JpaRepository<Runbook, Long> {

    @EntityGraph(attributePaths = {"service", "author", "categories"})
    List<Runbook> findAllByOrderByTitleAsc();

    @EntityGraph(attributePaths = {"service", "author", "categories"})
    Optional<Runbook> findWithDetailsById(Long id);

    boolean existsByTitleIgnoreCase(String title);

    long countByActiveTrue();

    @Query("select e.runbook.id, count(e) from RunbookExecution e group by e.runbook.id")
    List<Object[]> countExecutions();
}
