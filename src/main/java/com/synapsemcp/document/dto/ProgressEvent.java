package com.synapsemcp.document.dto;

import java.util.UUID;

/** Payload of the SSE {@code progress} event, per design.md section 7.2. */
public record ProgressEvent(UUID documentId, String stage, int progressPercent, String status) {
}
