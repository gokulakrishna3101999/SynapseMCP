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
                new ByteArrayInputStream(new byte[3]), "application/vnd.ms-excel");

        assertThat(key).isEqualTo("tenants/" + tenant + "/staging/" + doc + "/Q3_Report__final_.xlsx");
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(captor.getValue().key()).isEqualTo(key);
        assertThat(captor.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
    }

    /**
     * Regression test for a real bug found live: {@code RequestBody.fromInputStream} wraps a stream that
     * cannot be re-read, so if the AWS SDK ever needs to retry the request or re-sign it, it fails with
     * "Content input stream does not support mark/reset" instead of actually retrying. The fix buffers the
     * (already 10MiB-capped) file content into a plain byte array first, which {@code RequestBody.fromBytes}
     * can replay - proven here by opening the captured body's stream twice and getting the same content
     * both times, which the old stream-wrapping implementation could not do.
     */
    @Test
    void requestBodyIsReplayableSoTheSdkCanActuallyRetry() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        byte[] content = "hello world".getBytes();

        service.stageFile(tenant, doc, "a.txt", new ByteArrayInputStream(content), "text/plain");

        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(any(PutObjectRequest.class), bodyCaptor.capture());
        RequestBody body = bodyCaptor.getValue();

        byte[] firstRead = body.contentStreamProvider().newStream().readAllBytes();
        byte[] secondRead = body.contentStreamProvider().newStream().readAllBytes();
        assertThat(firstRead).isEqualTo(content);
        assertThat(secondRead).isEqualTo(content);
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
