package com.nexus360.knowledge;

import com.nexus360.common.PageResponse;
import com.nexus360.incident.Category;
import com.nexus360.knowledge.KnowledgeArticle.ArticleType;
import com.nexus360.knowledge.KnowledgeArticle.Status;
import com.nexus360.knowledge.KnowledgeDtos.ArticleDetail;
import com.nexus360.knowledge.KnowledgeDtos.ArticleRequest;
import com.nexus360.knowledge.KnowledgeDtos.ArticleSummary;
import com.nexus360.knowledge.KnowledgeDtos.FeedbackRequest;
import com.nexus360.knowledge.KnowledgeDtos.KnowledgeStats;
import com.nexus360.knowledge.KnowledgeDtos.VersionDto;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Arrays;
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

@Tag(name = "Knowledge base")
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService service;

    @GetMapping
    public PageResponse<ArticleSummary> list(@RequestParam(required = false) String q,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(required = false) Category category,
                                             @RequestParam(required = false) Long serviceId,
                                             @RequestParam(required = false) Status status,
                                             @RequestParam(required = false) String sort,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        List<ArticleType> types = type == null || type.isBlank() ? List.of()
                : Arrays.stream(type.split(",")).map(String::trim).map(ArticleType::valueOf).toList();
        ArticleType single = types.size() == 1 ? types.get(0) : null;
        return service.list(q, single, types.size() > 1 ? types : List.of(), category, serviceId, status, sort, page, size,
                CurrentActor.require());
    }

    @GetMapping("/stats")
    public KnowledgeStats stats() {
        return service.stats();
    }

    @GetMapping("/{id}")
    public ArticleDetail get(@PathVariable Long id) {
        return service.get(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ArticleDetail create(@Valid @RequestBody ArticleRequest request) {
        return service.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PutMapping("/{id}")
    public ArticleDetail update(@PathVariable Long id, @Valid @RequestBody ArticleRequest request) {
        return service.update(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/{id}/submit")
    public ArticleDetail submit(@PathVariable Long id) {
        return service.submit(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.KB_PUBLISHERS)
    @PostMapping("/{id}/publish")
    public ArticleDetail publish(@PathVariable Long id) {
        return service.publish(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.KB_PUBLISHERS)
    @PostMapping("/{id}/archive")
    public ArticleDetail archive(@PathVariable Long id) {
        return service.archive(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.KB_DELETERS)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id, CurrentActor.require());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/versions")
    public List<VersionDto> versions(@PathVariable Long id) {
        return service.versions(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.KB_PUBLISHERS)
    @PostMapping("/{id}/versions/{version}/restore")
    public ArticleDetail restore(@PathVariable Long id, @PathVariable int version) {
        return service.restore(id, version, CurrentActor.require());
    }

    @PostMapping("/{id}/feedback")
    public ArticleDetail feedback(@PathVariable Long id, @Valid @RequestBody FeedbackRequest request) {
        return service.feedback(id, request.rating(), request.helpful(), request.comment(), CurrentActor.require());
    }
}
