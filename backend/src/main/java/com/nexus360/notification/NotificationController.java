package com.nexus360.notification;

import com.nexus360.common.PageResponse;
import com.nexus360.notification.NotificationService.NotificationDto;
import com.nexus360.notification.NotificationService.PreferenceDto;
import com.nexus360.security.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;
    private final NotificationStreamHub hub;

    @GetMapping
    public PageResponse<NotificationDto> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return service.list(unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", service.unreadCount());
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id) {
        service.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", service.markAllRead());
    }

    @GetMapping("/preferences")
    public List<PreferenceDto> preferences() {
        return service.preferences(CurrentActor.require().id());
    }

    @Operation(summary = "Switch notification categories on or off, e.g. {\"SLA_AT_RISK\": false} (in-app) or "
            + "{\"APPROVAL_REQUESTED\": {\"inApp\": true, \"email\": false}}")
    @PutMapping("/preferences")
    public List<PreferenceDto> updatePreferences(@RequestBody Map<String, Object> changes) {
        return service.updatePreferences(CurrentActor.require().id(), changes);
    }

    @Operation(summary = "Server-Sent Events: 'notification' events (NotificationDto JSON) and a 'ping' every 25 s")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletResponse response) {
        // Proxies (nginx, Vercel) must not buffer the stream.
        response.setHeader("X-Accel-Buffering", "no");
        return hub.subscribe(CurrentActor.require().id());
    }
}
