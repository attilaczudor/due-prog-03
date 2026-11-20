package hu.due.rewear.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrCodeServiceTest {

    private final QrCodeService qrService = new QrCodeService();

    @Test
    void generatesAndDecodesStructuredProductCodeRoundTrip(@TempDir Path tempDir) throws IOException {
        String code = "2026-11-12-11-23-L-TSH-A-A-25-3F";

        BufferedImage image = qrService.generateQrImage(code, 300);
        assertEquals(300, image.getWidth());
        assertEquals(300, image.getHeight());

        Optional<String> decodedFromMemory = qrService.decodeQrFromImage(image);
        assertTrue(decodedFromMemory.isPresent());
        assertEquals(code, decodedFromMemory.get());

        Path pngFile = tempDir.resolve("qr-label.png");
        qrService.saveQrPng(code, 400, pngFile);
        assertTrue(Files.exists(pngFile));
        assertTrue(Files.size(pngFile) > 200);

        Optional<String> decodedFromFile = qrService.decodeQrFromFile(pngFile);
        assertTrue(decodedFromFile.isPresent());
        assertEquals(code, decodedFromFile.get());
    }
}
