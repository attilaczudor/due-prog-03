package hu.due.rewear.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generates and decodes QR codes using Google ZXing.
 */
public final class QrCodeService {

    public BufferedImage generateQrImage(String content, int sizePx) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("QR content must not be blank");
        }
        int safeSize = Math.max(64, sizePx);
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);

        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content.trim(), BarcodeFormat.QR_CODE, safeSize, safeSize, hints);
            return MatrixToImageWriter.toBufferedImage(matrix);
        } catch (WriterException e) {
            throw new IllegalStateException("Failed to generate QR code for: " + content, e);
        }
    }

    public void saveQrPng(String content, int sizePx, Path targetPath) throws IOException {
        if (targetPath.getParent() != null) {
            Files.createDirectories(targetPath.getParent());
        }
        BufferedImage img = generateQrImage(content, sizePx);
        ImageIO.write(img, "PNG", targetPath.toFile());
    }

    public Optional<String> decodeQrFromImage(BufferedImage image) {
        if (image == null) {
            return Optional.empty();
        }
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());

        LuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        try {
            Result result = new MultiFormatReader().decode(bitmap, hints);
            return Optional.ofNullable(result.getText()).map(String::trim);
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    public Optional<String> decodeQrFromFile(Path imageFile) throws IOException {
        BufferedImage image = ImageIO.read(imageFile.toFile());
        if (image == null) {
            throw new IOException("Unsupported or unreadable image file: " + imageFile.getFileName());
        }
        return decodeQrFromImage(image);
    }
}
