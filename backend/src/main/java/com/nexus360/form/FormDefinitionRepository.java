package com.nexus360.form;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FormDefinitionRepository extends JpaRepository<FormDefinition, Long>, JpaSpecificationExecutor<FormDefinition> {

    Optional<FormDefinition> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);
}
