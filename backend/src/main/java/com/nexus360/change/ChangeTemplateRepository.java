package com.nexus360.change;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangeTemplateRepository extends JpaRepository<ChangeTemplate, Long> {

    List<ChangeTemplate> findAllByOrderByNameAsc();

    Optional<ChangeTemplate> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);
}
