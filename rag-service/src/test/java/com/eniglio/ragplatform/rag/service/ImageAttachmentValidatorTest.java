package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.rag.exception.InvalidImageAttachmentException;
import com.eniglio.ragplatform.rag.exception.UnsupportedImageTypeException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageAttachmentValidatorTest {

    private final ImageAttachmentValidator validator = new ImageAttachmentValidator();

    @Test
    void acceptsAValidPng() {
        MockMultipartFile image = new MockMultipartFile("image", "diagram.png", "image/png", pngBytes());

        ValidatedImage validated = validator.validate(image);

        assertThat(validated.mimeType().toString()).isEqualTo("image/png");
        assertThat(validated.bytes()).isEqualTo(pngBytes());
    }

    @Test
    void acceptsAValidJpeg() {
        MockMultipartFile image = new MockMultipartFile("image", "photo.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0});

        ValidatedImage validated = validator.validate(image);

        assertThat(validated.mimeType().toString()).isEqualTo("image/jpeg");
    }

    @Test
    void acceptsAValidGif() {
        MockMultipartFile image = new MockMultipartFile("image", "anim.gif", "image/gif",
                "GIF89a".getBytes(StandardCharsets.US_ASCII));

        ValidatedImage validated = validator.validate(image);

        assertThat(validated.mimeType().toString()).isEqualTo("image/gif");
    }

    @Test
    void acceptsAValidWebp() {
        MockMultipartFile image = new MockMultipartFile("image", "pic.webp", "image/webp", riffWebp());

        ValidatedImage validated = validator.validate(image);

        assertThat(validated.mimeType().toString()).isEqualTo("image/webp");
    }

    @Test
    void rejectsAnUnsupportedContentType() {
        MockMultipartFile image = new MockMultipartFile("image", "doc.pdf", "application/pdf", pngBytes());

        assertThatThrownBy(() -> validator.validate(image))
                .isInstanceOf(UnsupportedImageTypeException.class);
    }

    @Test
    void rejectsAMissingContentType() {
        MockMultipartFile image = new MockMultipartFile("image", "unknown", null, pngBytes());

        assertThatThrownBy(() -> validator.validate(image))
                .isInstanceOf(UnsupportedImageTypeException.class);
    }

    @Test
    void rejectsBytesThatDoNotMatchTheDeclaredType() {
        MockMultipartFile image = new MockMultipartFile("image", "fake.png", "image/png",
                "not actually a png".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> validator.validate(image))
                .isInstanceOf(InvalidImageAttachmentException.class);
    }

    @Test
    void rejectsAJpegDisguisedAsAPng() {
        MockMultipartFile image = new MockMultipartFile("image", "sneaky.png", "image/png",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        assertThatThrownBy(() -> validator.validate(image))
                .isInstanceOf(InvalidImageAttachmentException.class);
    }

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    }

    private static byte[] riffWebp() {
        byte[] bytes = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        return bytes;
    }
}
