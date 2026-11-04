package com.nexus360.cmdb;

import com.nexus360.cmdb.CmdbDtos.CiDetail;
import com.nexus360.cmdb.CmdbDtos.CiRequest;
import com.nexus360.cmdb.CmdbDtos.CiSummary;
import com.nexus360.cmdb.CmdbDtos.CmdbMeta;
import com.nexus360.cmdb.CmdbDtos.Graph;
import com.nexus360.cmdb.CmdbDtos.HistoryDto;
import com.nexus360.cmdb.CmdbDtos.ImpactAnalysis;
import com.nexus360.cmdb.CmdbDtos.RelationshipDto;
import com.nexus360.cmdb.CmdbDtos.RelationshipRequest;
import com.nexus360.cmdb.ConfigurationItem.CiType;
import com.nexus360.cmdb.ConfigurationItem.Criticality;
import com.nexus360.cmdb.ConfigurationItem.Environment;
import com.nexus360.cmdb.ConfigurationItem.Status;
import com.nexus360.common.PageResponse;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "CMDB")
@RestController
@RequestMapping("/api/cmdb")
@RequiredArgsConstructor
public class CmdbController {

    private final CmdbService service;
    private final CmdbGraphService graph;

    @PreAuthorize(Roles.READERS)
    @GetMapping("/meta")
    public CmdbMeta meta() {
        return service.meta();
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/cis")
    public PageResponse<CiSummary> list(@RequestParam(required = false) String search, @RequestParam(required = false) List<CiType> type,
                                        @RequestParam(required = false) List<Status> status, @RequestParam(required = false) Environment environment,
                                        @RequestParam(required = false) Criticality criticality, @RequestParam(required = false) Long teamId,
                                        @RequestParam(required = false) Long ownerId, @RequestParam(required = false) Long serviceId,
                                        @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String sort) {
        return service.list(new CmdbService.Filter(search, type, status, environment, criticality, teamId, ownerId, serviceId), page, size, sort);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/cis/{id}")
    public CiDetail get(@PathVariable Long id) {
        return service.get(id);
    }

    @PreAuthorize(Roles.CMDB_EDITORS)
    @PostMapping("/cis")
    @ResponseStatus(HttpStatus.CREATED)
    public CiDetail create(@Valid @RequestBody CiRequest request) {
        return service.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.CMDB_EDITORS)
    @PutMapping("/cis/{id}")
    public CiDetail update(@PathVariable Long id, @Valid @RequestBody CiRequest request) {
        return service.update(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.CMDB_EDITORS)
    @DeleteMapping("/cis/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/cis/{id}/relationships")
    public List<RelationshipDto> relationships(@PathVariable Long id) {
        return service.relationships(id);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/cis/{id}/history")
    public List<HistoryDto> history(@PathVariable Long id) {
        return service.history(id);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/cis/{id}/impact")
    public ImpactAnalysis impact(@PathVariable Long id, @RequestParam(required = false) String direction, @RequestParam(required = false) Integer depth) {
        return graph.impact(id, direction, depth);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/graph")
    public Graph graph(@RequestParam(required = false) Long ciId, @RequestParam(required = false) Integer depth,
                       @RequestParam(required = false) List<CiType> type, @RequestParam(required = false) Environment environment,
                       @RequestParam(defaultValue = "false") boolean includeRetired) {
        return graph.graph(ciId, depth, type, environment, includeRetired);
    }

    @PreAuthorize(Roles.CMDB_EDITORS)
    @PostMapping("/relationships")
    @ResponseStatus(HttpStatus.CREATED)
    public RelationshipDto addRelationship(@Valid @RequestBody RelationshipRequest request) {
        return service.addRelationship(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.CMDB_EDITORS)
    @DeleteMapping("/relationships/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeRelationship(@PathVariable Long id) {
        service.removeRelationship(id, CurrentActor.require());
    }
}
