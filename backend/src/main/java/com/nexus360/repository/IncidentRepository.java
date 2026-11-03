package com.nexus360.repository;
import com.nexus360.model.Incident;import org.springframework.data.jpa.repository.JpaRepository;import java.util.List;
public interface IncidentRepository extends JpaRepository<Incident,String>{List<Incident> findByStatusIgnoreCase(String status);List<Incident> findByPriorityIgnoreCase(String priority);}
