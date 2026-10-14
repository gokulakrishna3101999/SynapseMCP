package com.synapsemcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.exception.ApiException;
import com.synapsemcp.document.dto.UploadResponse;
import com.synapsemcp.document.entity.IngestionTier;
import com.synapsemcp.document.service.DocumentService;
import com.synapsemcp.ingestion.service.IngestionOrchestrator;
import com.synapsemcp.security.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code upload_document} must be a thin adapter onto the exact same {@link DocumentService#upload} +
 * {@link IngestionOrchestrator} pipeline the REST upload endpoint uses - these tests verify the Base64
 * decode and the {@link MultipartFile} adaptation are correct, not re-test {@code DocumentService} itself
 * (already covered by {@code DocumentServiceTierTest} etc.).
 */
class UploadDocumentToolTest {

    private final DocumentService documentService = mock(DocumentService.class);
    private final IngestionOrchestrator orchestrator = mock(IngestionOrchestrator.class);
    private final UploadDocumentTool tool = new UploadDocumentTool(documentService, orchestrator);
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void bindTenant() {
        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void decodesBase64AndPassesTheExactBytesAndFilenameThroughAsAMultipartFile() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(documentService.upload(any())).thenReturn(new UploadResponse(
                documentId, jobId, "report.pdf", 11, IngestionTier.TIER_2_JAVA_PARSER, "QUEUED", false));

        String original = "real file content, not a placeholder";
        String encoded = Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8));

        UploadResponse response = tool.upload("report.pdf", encoded);

        assertThat(response.documentId()).isEqualTo(documentId);

        org.mockito.ArgumentCaptor<MultipartFile> captor = org.mockito.ArgumentCaptor.forClass(MultipartFile.class);
        verify(documentService).upload(captor.capture());
        MultipartFile captured = captor.getValue();
        assertThat(captured.getOriginalFilename()).isEqualTo("report.pdf");
        assertThat(new String(captured.getBytes(), StandardCharsets.UTF_8)).isEqualTo(original);
        assertThat(captured.getSize()).isEqualTo(original.getBytes(StandardCharsets.UTF_8).length);
        assertThat(captured.isEmpty()).isFalse();
    }

    @Test
    void triggersAsyncIngestionWithTheIdsUploadReturned() {
        UUID documentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(documentService.upload(any())).thenReturn(new UploadResponse(
                documentId, jobId, "a.txt", 1, IngestionTier.TIER_2_JAVA_PARSER, "QUEUED", false));

        tool.upload("a.txt", Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8)));

        verify(orchestrator).processDocumentAsync(jobId, documentId, tenantId);
    }

    @Test
    void malformedBase64FailsWithAClearMessageInsteadOfAnUncaughtException() {
        assertThatThrownBy(() -> tool.upload("a.txt", "not valid base64!!!"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    void emptyDecodedContentIsStillPassedThroughForDocumentServiceToReject() {
        when(documentService.upload(any())).thenThrow(new ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "EMPTY_FILE", "Uploaded file is empty."));

        assertThatThrownBy(() -> tool.upload("empty.txt", ""))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("empty");
    }
}
