package com.synapsemcp.document.service;

import com.synapsemcp.document.dto.ProgressEvent;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans out ingestion progress to any SSE clients connected for a given document. A client can connect
 * before or after the worker starts (or not at all); broadcasting to zero emitters is a no-op, and the
 * polling fallback (GET /status) covers clients that missed the stream, per the risk table in plan.md.
 */
@Service
public class IngestionProgressSseService {

    private static final Logger log = LoggerFactory.getLogger(IngestionProgressSseService.class);
    private static final long EMITTER_TIMEOUT_MILLIS = 15 * 60 * 1000L;

    private final Map<UUID, List<SseEmitter>> emittersByDocument = new ConcurrentHashMap<>();

    public SseEmitter registerClient(UUID documentId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        List<SseEmitter> emitters = emittersByDocument.computeIfAbsent(documentId, id -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);
        Runnable remove = () -> emitters.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ex -> remove.run());
        return emitter;
    }

    public void broadcastProgress(UUID documentId, String stage, int progressPercent, String status) {
        send(documentId, "progress", new ProgressEvent(documentId, stage, progressPercent, status));
    }

    public void broadcastError(UUID documentId, String errorMessage) {
        send(documentId, "error", Map.of("documentId", documentId, "error", errorMessage));
    }

    public void completeStream(UUID documentId) {
        List<SseEmitter> emitters = emittersByDocument.remove(documentId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            emitter.complete();
        }
    }

    private void send(UUID documentId, String eventName, Object payload) {
        List<SseEmitter> emitters = emittersByDocument.get(documentId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException ex) {
                log.debug("Dropping disconnected progress-stream client for document {}", documentId, ex);
                emitter.completeWithError(ex);
            }
        }
    }
}
