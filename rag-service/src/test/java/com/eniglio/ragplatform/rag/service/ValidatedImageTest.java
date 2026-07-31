package com.eniglio.ragplatform.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.util.MimeType;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatedImageTest {

    @Test
    void twoInstancesWithContentEqualButDistinctArraysAreEqual() {
        ValidatedImage a = new ValidatedImage(new byte[] {1, 2, 3}, MimeType.valueOf("image/png"));
        ValidatedImage b = new ValidatedImage(new byte[] {1, 2, 3}, MimeType.valueOf("image/png"));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void differentBytesAreNotEqual() {
        ValidatedImage a = new ValidatedImage(new byte[] {1, 2, 3}, MimeType.valueOf("image/png"));
        ValidatedImage b = new ValidatedImage(new byte[] {4, 5, 6}, MimeType.valueOf("image/png"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void toStringReportsByteCountNotRawContent() {
        ValidatedImage image = new ValidatedImage(new byte[] {1, 2, 3}, MimeType.valueOf("image/png"));

        assertThat(image.toString()).contains("3 bytes").contains("image/png");
    }
}
