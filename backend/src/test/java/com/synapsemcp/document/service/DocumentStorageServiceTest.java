package com.synapsemcp.document.service;

import com.synapsemcp.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DocumentStorageServiceTest {

    private final S3Client s3 = mock(S3Client.class);
    private final DocumentStorageService service =
            new DocumentStorageService(s3, new StorageProperties("test-bucket", "us-east-1", "test-access", "test-secret", ""));

    @Test
    void stagesUnderTenantStagingPrefixWithEncryption() {
        UUID tenant = UUID.randomUUID();
        UUID doc = UUID.randomUUID();

        String key = service.stageFile(tenant, doc, "Q3 Report (final).xlsx",
                new ByteArrayInputStream(new byte[3]), 3, "application/vnd.ms-excel");

        assertThat(key).isEqualTo("tenants/" + tenant + "/staging/" + doc + "/Q3_Report__final_.xlsx");
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(captor.getValue().key()).isEqualTo(key);
        assertThat(captor.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
    }

    @Test
    void purgeDeletesExactKey() {
        service.purgeStagedFile("tenants/t/staging/d/file.pdf");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(captor.getValue().key()).isEqualTo("tenants/t/staging/d/file.pdf");
    }

    @Test
    void sanitizeStripsPathsAndTraversal() {
        assertThat(DocumentStorageService.sanitize("../../etc/passwd")).isEqualTo("passwd");
        assertThat(DocumentStorageService.sanitize("C:\\temp\\a b.pdf")).isEqualTo("a_b.pdf");
        assertThat(DocumentStorageService.sanitize("..")).isEqualTo("upload");
        assertThat(DocumentStorageService.sanitize(null)).isEqualTo("upload");
    }
}
