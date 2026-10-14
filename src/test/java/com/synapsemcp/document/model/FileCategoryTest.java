package com.synapsemcp.document.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileCategoryTest {

    @Test
    void resolvesAllSevenCategoriesCaseInsensitively() {
        assertThat(FileCategory.resolve("a.PDF").orElseThrow().category()).isEqualTo(FileCategory.PDF);
        assertThat(FileCategory.resolve("a.docx").orElseThrow().category()).isEqualTo(FileCategory.WORD);
        assertThat(FileCategory.resolve("a.xls").orElseThrow().category()).isEqualTo(FileCategory.EXCEL);
        assertThat(FileCategory.resolve("a.pptx").orElseThrow().category()).isEqualTo(FileCategory.POWERPOINT);
        assertThat(FileCategory.resolve("a.htm").orElseThrow().category()).isEqualTo(FileCategory.HTML);
        assertThat(FileCategory.resolve("a.json").orElseThrow().category()).isEqualTo(FileCategory.TEXT);
        assertThat(FileCategory.resolve("a.webp").orElseThrow().category()).isEqualTo(FileCategory.IMAGE);
    }

    @Test
    void rejectsUnsupportedOrExtensionless() {
        assertThat(FileCategory.resolve("malware.exe")).isEmpty();
        assertThat(FileCategory.resolve("noextension")).isEmpty();
        assertThat(FileCategory.resolve("trailingdot.")).isEmpty();
        assertThat(FileCategory.resolve(null)).isEmpty();
    }
}
