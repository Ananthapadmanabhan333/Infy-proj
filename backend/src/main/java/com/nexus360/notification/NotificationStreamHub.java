package com.nexus360.notification;

import com.nexus360.notification.NotificationService.NotificationDto;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events fan-out: one {@link SseEmitter} per open browser tab, keyed by user. Notifications are pushed
 * after their transaction commits (see {@link NotificationService}); a {@code ping} event every 25 seconds keeps
 * proxies from closing idle connections and lets clients detect a dead stream. Single-instance by design: with several
 * API instances a user only receives pushes from the instance holding the connection, and the SPA's 30 s polling
 * fallback still picks up the rest.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationStreamHub {

    static final long TIMEOUT_MS = 30 * 60 * 1000L;
    static final int MAX_STREAMS_PER_USER = 5;

    private final Map<Long, List<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final Clock clock;

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> list = streams.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        while (list.size() >= MAX_STREAMS_PER_USER) {
            SseEmitter oldest = list.remove(0);
            oldest.complete();
        }
        list.add(emitter);
        Runnable remove = () -> remove(userId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(e -> remove.run());
        // An immediate event flushes the response headers so the client knows the stream is open.
        send(userId, emitter, SseEmitter.event().name("ping").data(Map.of("at", clock.instant().toString()), MediaType.APPLICATION_JSON));
        return emitter;
    }

    public void publish(Long userId, NotificationDto notification) {
        List<SseEmitter> list = streams.get(userId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            send(userId, emitter, SseEmitter.event().id(String.valueOf(notification.id())).name("notification")
                    .data(notification, MediaType.APPLICATION_JSON));
        }
    }

    @Scheduled(fixedRateString = "${app.notifications.ping-interval-ms:25000}", initialDelay = 25_000)
    public void ping() {
        String at = clock.instant().toString();
        streams.forEach((userId, list) -> list.forEach(emitter ->
                send(userId, emitter, SseEmitter.event().name("ping").data(Map.of("at", at), MediaType.APPLICATION_JSON))));
    }

    public int openStreams(Long userId) {
        List<SseEmitter> list = streams.get(userId);
        return list == null ? 0 : list.size();
    }

    /** Closes every stream of a user (used when the user signs out or is deactivated, and by tests). */
    public void disconnect(Long userId) {
        List<SseEmitter> list = streams.remove(userId);
        if (list != null) {
            list.forEach(SseEmitter::complete);
        }
    }

    private void send(Long userId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // The client has gone; the servlet container completes the async request itself (per ResponseBodyEmitter
            // docs), so only the registry entry is dropped here.
            log.debug("Dropping notification stream of user {}: {}", userId, e.getMessage());
            remove(userId, emitter);
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        List<SseEmitter> list = streams.get(userId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                streams.remove(userId, list);
            }
        }
    }
}
