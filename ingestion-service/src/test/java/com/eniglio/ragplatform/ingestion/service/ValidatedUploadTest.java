package com.eniglio.ragplatform.ingestion.service;

import org.junit.jupiter.api.Test;
import org.springframework.util.MimeType;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatedUploadTest {

    @Test
    void twoInstancesWithContentEqualButDistinctArraysAreEqual() {
        ValidatedUpload a = new ValidatedUpload(
                new byte[] {1, 2, 3}, "doc.pdf", MimeType.valueOf("application/pdf"), DocumentKind.PDF);
        ValidatedUpload b = new ValidatedUpload(
                new byte[] {1, 2, 3}, "doc.pdf", MimeType.valueOf("application/pdf"), DocumentKind.PDF);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void differentBytesAreNotEqual() {
        ValidatedUpload a = new ValidatedUpload(
                new byte[] {1, 2, 3}, "doc.pdf", MimeType.valueOf("application/pdf"), DocumentKind.PDF);
        ValidatedUpload b = new ValidatedUpload(
                new byte[] {4, 5, 6}, "doc.pdf", MimeType.valueOf("application/pdf"), DocumentKind.PDF);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void toStringReportsByteCountNotRawContent() {
        ValidatedUpload upload = new ValidatedUpload(
                new byte[] {1, 2, 3}, "doc.pdf", MimeType.valueOf("application/pdf"), DocumentKind.PDF);

        assertThat(upload.toString()).contains("3 bytes").contains("doc.pdf").contains("PDF");
    }
}
