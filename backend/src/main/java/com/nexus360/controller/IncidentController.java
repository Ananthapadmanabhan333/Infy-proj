package com.nexus360.controller;
import com.nexus360.model.Incident;import com.nexus360.repository.IncidentRepository;import jakarta.validation.Valid;import org.springframework.http.HttpStatus;import org.springframework.web.bind.annotation.*;import org.springframework.web.server.ResponseStatusException;import java.util.List;import java.util.Map;
@RestController @RequestMapping("/api/incidents")
public class IncidentController {
 private final IncidentRepository repo; public IncidentController(IncidentRepository repo){this.repo=repo;}
 @GetMapping public List<Incident> all(@RequestParam(required=false) String status,@RequestParam(required=false) String priority){if(status!=null)return repo.findByStatusIgnoreCase(status);if(priority!=null)return repo.findByPriorityIgnoreCase(priority);return repo.findAll();}
 @GetMapping("/{id}") public Incident one(@PathVariable String id){return repo.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Incident not found"));}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) public Incident create(@Valid @RequestBody Incident input){input.setId(null);return repo.save(input);}
 @PutMapping("/{id}") public Incident update(@PathVariable String id,@Valid @RequestBody Incident input){Incident x=one(id);x.setTitle(input.getTitle());x.setPriority(input.getPriority());x.setService(input.getService());x.setAssignee(input.getAssignee());x.setStatus(input.getStatus());x.setSla(input.getSla());return repo.save(x);}
 @PatchMapping("/{id}/resolve") public Incident resolve(@PathVariable String id){Incident x=one(id);x.setStatus("Resolved");x.setSla("—");return repo.save(x);}
 @PatchMapping("/{id}/assign") public Incident assign(@PathVariable String id,@RequestBody Map<String,String> body){Incident x=one(id);x.setAssignee(body.getOrDefault("assignee","Unassigned"));return repo.save(x);}
 @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id){repo.delete(one(id));}
}
