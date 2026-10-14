package com.synapsemcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ByteArrayMultipartFileTest {

    @Test
    void reportsTheGivenFilenameAndContentCorrectly() throws Exception {
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        ByteArrayMultipartFile file = new ByteArrayMultipartFile("notes.txt", content);

        assertThat(file.getOriginalFilename()).isEqualTo("notes.txt");
        assertThat(file.getSize()).isEqualTo(content.length);
        assertThat(file.isEmpty()).isFalse();
        assertThat(file.getBytes()).isEqualTo(content);
        assertThat(file.getInputStream().readAllBytes()).isEqualTo(content);
    }

    @Test
    void zeroLengthContentIsReportedAsEmpty() {
        ByteArrayMultipartFile file = new ByteArrayMultipartFile("empty.txt", new byte[0]);
        assertThat(file.isEmpty()).isTrue();
        assertThat(file.getSize()).isZero();
    }

    @Test
    void transferToWritesTheExactBytesToDisk(@TempDir File tempDir) throws Exception {
        byte[] content = "transferred content".getBytes(StandardCharsets.UTF_8);
        ByteArrayMultipartFile file = new ByteArrayMultipartFile("x.txt", content);
        File dest = new File(tempDir, "out.txt");

        file.transferTo(dest);

        assertThat(Files.readAllBytes(dest.toPath())).isEqualTo(content);
    }
}
