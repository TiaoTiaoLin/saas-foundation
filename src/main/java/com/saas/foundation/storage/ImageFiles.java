package com.saas.foundation.storage;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Turns an uploaded JPEG, PNG, or WebP into a 256-pixel JPEG. */
public final class ImageFiles {

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int SIDE = 256;
    public static final String TOO_LARGE = "Image must be at most 2 MB";
    public static final String UNSUPPORTED = "Choose a JPEG, PNG, or WebP image";

    private static final int MAX_SOURCE_SIDE = 4096;

    private ImageFiles() {
    }

    public static byte[] jpeg(byte[] source) {
        if (source == null || source.length == 0) {
            throw bad(UNSUPPORTED);
        }
        if (source.length > MAX_BYTES) {
            throw bad(TOO_LARGE);
        }
        if (!looksLikeJpeg(source) && !looksLikePng(source) && !looksLikeWebp(source)) {
            throw bad(UNSUPPORTED);
        }
        BufferedImage decoded = decode(source);
        if (decoded.getWidth() > MAX_SOURCE_SIDE || decoded.getHeight() > MAX_SOURCE_SIDE) {
            throw bad(UNSUPPORTED);
        }
        return writeJpeg(square(decoded));
    }

    private static boolean looksLikeJpeg(byte[] source) {
        return source.length >= 3
                && (source[0] & 0xFF) == 0xFF
                && (source[1] & 0xFF) == 0xD8
                && (source[2] & 0xFF) == 0xFF;
    }

    private static boolean looksLikePng(byte[] source) {
        return source.length >= 8
                && (source[0] & 0xFF) == 0x89
                && source[1] == 0x50
                && source[2] == 0x4E
                && source[3] == 0x47
                && source[4] == 0x0D
                && source[5] == 0x0A
                && source[6] == 0x1A
                && source[7] == 0x0A;
    }

    private static boolean looksLikeWebp(byte[] source) {
        return source.length >= 12
                && source[0] == 'R'
                && source[1] == 'I'
                && source[2] == 'F'
                && source[3] == 'F'
                && source[8] == 'W'
                && source[9] == 'E'
                && source[10] == 'B'
                && source[11] == 'P';
    }

    private static BufferedImage decode(byte[] source) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null) {
                throw bad(UNSUPPORTED);
            }
            return image;
        } catch (IOException ex) {
            throw bad(UNSUPPORTED);
        }
    }

    private static BufferedImage square(BufferedImage source) {
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;
        BufferedImage cropped = source.getSubimage(x, y, side, side);
        BufferedImage output = new BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, SIDE, SIDE);
            graphics.drawImage(cropped, 0, 0, SIDE, SIDE, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static byte[] writeJpeg(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("JPEG writer is not available");
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw bad(UNSUPPORTED);
        } finally {
            writer.dispose();
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
