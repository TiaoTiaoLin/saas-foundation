package com.saas.foundation.storage;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Reads the multipart part named {@code file}. */
public final class ImageUploads {

    private ImageUploads() {
    }

    public static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an image");
        }
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ImageFiles.UNSUPPORTED);
        }
    }
}
