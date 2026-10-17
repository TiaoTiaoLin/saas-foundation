package com.saas.foundation.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ImageFilesTest {

    @Test
    void pngBecomesAJpeg() throws Exception {
        BufferedImage image = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x112233);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);

        byte[] jpeg = ImageFiles.jpeg(out.toByteArray());

        assertThat(jpeg).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
    }

    @Test
    void svgIsRejected() {
        assertThatThrownBy(() -> ImageFiles.jpeg("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes()))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(ImageFiles.UNSUPPORTED));
    }

    @Test
    void oversizedBytesAreRejected() {
        assertThatThrownBy(() -> ImageFiles.jpeg(new byte[ImageFiles.MAX_BYTES + 1]))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(ImageFiles.TOO_LARGE));
    }

    @Test
    void webpHeaderThatDoesNotDecodeIsRejected() {
        byte[] fake = new byte[16];
        fake[0] = 'R';
        fake[1] = 'I';
        fake[2] = 'F';
        fake[3] = 'F';
        fake[8] = 'W';
        fake[9] = 'E';
        fake[10] = 'B';
        fake[11] = 'P';

        assertThatThrownBy(() -> ImageFiles.jpeg(fake))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(ImageFiles.UNSUPPORTED));
    }
}
