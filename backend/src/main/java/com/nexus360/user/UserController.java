package com.nexus360.user;

import com.nexus360.common.PageResponse;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import com.nexus360.user.UserDtos.CreateUserRequest;
import com.nexus360.user.UserDtos.DepartmentDto;
import com.nexus360.user.UserDtos.DepartmentRequest;
import com.nexus360.user.UserDtos.ResetPasswordRequest;
import com.nexus360.user.UserDtos.UpdateUserRequest;
import com.nexus360.user.UserDtos.UserDto;
import com.nexus360.user.UserDtos.UserSummary;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Users & departments")
@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService service;

    @PreAuthorize(Roles.USER_READERS)
    @GetMapping("/api/users")
    public PageResponse<UserDto> list(@RequestParam(required = false) String search, @RequestParam(required = false) Role role,
                                      @RequestParam(required = false) Long departmentId, @RequestParam(required = false) Boolean active,
                                      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.list(search, role, departmentId, active, page, size);
    }

    @PreAuthorize(Roles.STAFF)
    @GetMapping("/api/users/assignable")
    public List<UserSummary> assignable(@RequestParam(required = false) Long departmentId) {
        return service.assignable(departmentId);
    }

    @PreAuthorize(Roles.USER_READERS)
    @GetMapping("/api/users/{id}")
    public UserDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PreAuthorize(Roles.USER_ADMINS)
    @PostMapping("/api/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto create(@Valid @RequestBody CreateUserRequest request) {
        return service.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.USER_ADMINS)
    @PutMapping("/api/users/{id}")
    public UserDto update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return service.update(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.USER_ADMINS)
    @PostMapping("/api/users/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        service.resetPassword(id, request.newPassword(), CurrentActor.require());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/departments")
    public List<DepartmentDto> departments() {
        return service.departments();
    }

    @PreAuthorize(Roles.USER_ADMINS)
    @PostMapping("/api/departments")
    @ResponseStatus(HttpStatus.CREATED)
    public DepartmentDto createDepartment(@Valid @RequestBody DepartmentRequest request) {
        return service.saveDepartment(null, request);
    }

    @PreAuthorize(Roles.USER_ADMINS)
    @PutMapping("/api/departments/{id}")
    public DepartmentDto updateDepartment(@PathVariable Long id, @Valid @RequestBody DepartmentRequest request) {
        return service.saveDepartment(id, request);
    }
}
