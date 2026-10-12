package com.synapsemcp.ingestion.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.document.entity.IngestionTier;
import com.synapsemcp.document.model.FileCategory;
import org.junit.jupiter.api.Test;

class IngestionTierRouterTest {

    private static final long TWO_MIB = 2L * 1024 * 1024;

    private final IngestionTierRouter router = new IngestionTierRouter();

    @Test
    void pdfAndImageUpToTwoMibGoToGemini() {
        assertThat(router.route(FileCategory.PDF, 1)).isEqualTo(IngestionTier.TIER_1_MULTIMODAL);
        assertThat(router.route(FileCategory.PDF, TWO_MIB)).isEqualTo(IngestionTier.TIER_1_MULTIMODAL);
        assertThat(router.route(FileCategory.IMAGE, TWO_MIB)).isEqualTo(IngestionTier.TIER_1_MULTIMODAL);
    }

    @Test
    void pdfAndImageAboveTwoMibGoToJavaParsers() {
        assertThat(router.route(FileCategory.PDF, TWO_MIB + 1)).isEqualTo(IngestionTier.TIER_2_JAVA_PARSER);
        assertThat(router.route(FileCategory.IMAGE, TWO_MIB + 1)).isEqualTo(IngestionTier.TIER_2_JAVA_PARSER);
    }

    @Test
    void everyOtherCategoryUsesJavaParsersAtAnySize() {
        for (FileCategory category : FileCategory.values()) {
            if (category == FileCategory.PDF || category == FileCategory.IMAGE) {
                continue;
            }
            assertThat(router.route(category, 1)).as(category.name()).isEqualTo(IngestionTier.TIER_2_JAVA_PARSER);
            assertThat(router.route(category, TWO_MIB + 1)).as(category.name())
                    .isEqualTo(IngestionTier.TIER_2_JAVA_PARSER);
        }
    }
}
