package com.nexus360.problem;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemHistoryRepository extends JpaRepository<ProblemHistory, Long> {

    List<ProblemHistory> findByProblemIdOrderByCreatedAtAscIdAsc(Long problemId);
}
