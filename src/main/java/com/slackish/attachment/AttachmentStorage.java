package com.slackish.attachment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Stores attachment content on local disk. Keys are server-generated UUIDs, never client-supplied names. */
@Component
public class AttachmentStorage {

    private final Path root;

    public AttachmentStorage(@Value("${app.attachments.dir}") String dir) throws IOException {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public void save(String key, MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store attachment", e);
        }
    }

    public Resource load(String key) {
        return new FileSystemResource(resolve(key));
    }

    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete attachment", e);
        }
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.getParent().equals(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return p;
    }
}
