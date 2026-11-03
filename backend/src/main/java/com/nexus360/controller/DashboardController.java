package com.nexus360.controller;
import com.nexus360.repository.IncidentRepository;import org.springframework.web.bind.annotation.*;import java.util.Map;
@RestController @RequestMapping("/api/dashboard") @CrossOrigin(origins="*")
public class DashboardController {private final IncidentRepository repo;public DashboardController(IncidentRepository repo){this.repo=repo;}
 @GetMapping("/metrics") public Map<String,Object> metrics(){var all=repo.findAll();long critical=all.stream().filter(i->"Critical".equalsIgnoreCase(i.getPriority())&&!"Resolved".equalsIgnoreCase(i.getStatus())).count();long open=all.stream().filter(i->!"Resolved".equalsIgnoreCase(i.getStatus())).count();long resolved=all.size()-open;return Map.of("totalIncidents",all.size(),"openIncidents",open,"criticalIncidents",critical,"resolvedIncidents",resolved,"slaCompliance",96.4,"meanTimeToResolveMinutes",28);}
}
