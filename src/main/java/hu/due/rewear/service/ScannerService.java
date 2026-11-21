package hu.due.rewear.service;

import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.StockMovementDao;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.ParsedProductCode;
import hu.due.rewear.model.StockMovement;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Unified QR scanning pipeline for USB/Bluetooth HID keyboard-wedge scanners and QR image files.
 */
public final class ScannerService {

    public enum ScanOutcome {
        FOUND_IN_DB,
        VALID_FORMAT_NOT_IN_DB,
        INVALID_FORMAT,
        IMAGE_NO_QR_FOUND
    }

    public record ScanResult(
            ScanOutcome outcome,
            String rawInput,
            ParsedProductCode parsedCode,
            InventoryPackage inventoryPackage,
            List<StockMovement> history
    ) {
        public ScanResult {
            history = history == null ? List.of() : List.copyOf(history);
        }
    }

    private final ProductCodeService codeService;
    private final QrCodeService qrCodeService;
    private final InventoryPackageDao packageDao;
    private final StockMovementDao movementDao;

    public ScannerService(ProductCodeService codeService,
                          QrCodeService qrCodeService,
                          InventoryPackageDao packageDao,
                          StockMovementDao movementDao) {
        this.codeService = codeService;
        this.qrCodeService = qrCodeService;
        this.packageDao = packageDao;
        this.movementDao = movementDao;
    }

    public ScanResult scanCode(String rawInput) {
        String trimmed = rawInput == null ? "" : rawInput.trim();
        if (trimmed.isEmpty()) {
            return new ScanResult(ScanOutcome.INVALID_FORMAT, "", null, null, List.of());
        }

        Optional<ParsedProductCode> parsedOpt = codeService.parse(trimmed);
        Optional<InventoryPackage> pkgOpt = packageDao.findByQrCode(trimmed);

        if (pkgOpt.isPresent()) {
            InventoryPackage pkg = pkgOpt.get();
            List<StockMovement> history = movementDao.findByPackageId(pkg.id());
            return new ScanResult(
                    ScanOutcome.FOUND_IN_DB,
                    pkg.qrCode(),
                    parsedOpt.orElse(null),
                    pkg,
                    history
            );
        }

        if (parsedOpt.isPresent()) {
            return new ScanResult(
                    ScanOutcome.VALID_FORMAT_NOT_IN_DB,
                    parsedOpt.get().rawCode(),
                    parsedOpt.get(),
                    null,
                    List.of()
            );
        }

        return new ScanResult(ScanOutcome.INVALID_FORMAT, trimmed, null, null, List.of());
    }

    public ScanResult scanImageFile(Path imageFile) throws IOException {
        Optional<String> decoded = qrCodeService.decodeQrFromFile(imageFile);
        if (decoded.isEmpty()) {
            return new ScanResult(ScanOutcome.IMAGE_NO_QR_FOUND, imageFile.getFileName().toString(), null, null, List.of());
        }
        return scanCode(decoded.get());
    }
}
