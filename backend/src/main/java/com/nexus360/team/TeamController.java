package com.nexus360.team;

import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import com.nexus360.team.TeamDtos.MemberRequest;
import com.nexus360.team.TeamDtos.MemberRoleRequest;
import com.nexus360.team.TeamDtos.TeamDto;
import com.nexus360.team.TeamDtos.TeamRef;
import com.nexus360.team.TeamDtos.TeamRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

@Tag(name = "Teams")
@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService service;

    @PreAuthorize(Roles.READERS)
    @GetMapping
    public List<TeamDto> list(@RequestParam(required = false) Long departmentId, @RequestParam(required = false) Boolean active,
                              @RequestParam(required = false) String search) {
        return service.list(departmentId, active, search);
    }

    @Operation(summary = "Teams the caller is a member of")
    @GetMapping("/mine")
    public List<TeamRef> mine() {
        return service.mine(CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/{id}")
    public TeamDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PreAuthorize(Roles.TEAM_ADMINS)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TeamDto create(@Valid @RequestBody TeamRequest request) {
        return service.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.TEAM_ADMINS)
    @PutMapping("/{id}")
    public TeamDto update(@PathVariable Long id, @Valid @RequestBody TeamRequest request) {
        return service.update(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.TEAM_ADMINS)
    @PostMapping("/{id}/members")
    public TeamDto addMember(@PathVariable Long id, @Valid @RequestBody MemberRequest request) {
        return service.addMember(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.TEAM_ADMINS)
    @PutMapping("/{id}/members/{userId}")
    public TeamDto updateMember(@PathVariable Long id, @PathVariable Long userId, @Valid @RequestBody MemberRoleRequest request) {
        return service.updateMember(id, userId, request.memberRole(), CurrentActor.require());
    }

    @PreAuthorize(Roles.TEAM_ADMINS)
    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        service.removeMember(id, userId, CurrentActor.require());
        return ResponseEntity.noContent().build();
    }
}
