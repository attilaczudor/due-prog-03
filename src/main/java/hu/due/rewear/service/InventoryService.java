package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.StockMovementDao;
import hu.due.rewear.model.GarmentType;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.MovementType;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.PackageType;
import hu.due.rewear.model.QualityGrade;
import hu.due.rewear.model.TargetGroup;
import hu.due.rewear.model.WarehouseLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Coordinates transactional package intake, relocation, stocktake verification, and disposal.
 */
public final class InventoryService {

    private static final Logger LOG = LoggerFactory.getLogger(InventoryService.class);

    private final DatabaseManager db;
    private final InventoryPackageDao packageDao;
    private final StockMovementDao movementDao;
    private final ProductCodeService codeService;

    public InventoryService(DatabaseManager db,
                            InventoryPackageDao packageDao,
                            StockMovementDao movementDao,
                            ProductCodeService codeService) {
        this.db = db;
        this.packageDao = packageDao;
        this.movementDao = movementDao;
        this.codeService = codeService;
    }

    /**
     * Atomically creates {@code quantity} packages (1..50), each with a collision-free random 2-digit hex suffix,
     * and records an {@link MovementType#INTAKE} audit trail entry for each package.
     */
    public List<InventoryPackage> createPackages(
            TargetGroup targetGroup,
            GarmentType garmentType,
            QualityGrade qualityGrade,
            PackageType packageType,
            WarehouseLocation location,
            BigDecimal weightKg,
            int quantity,
            String notes,
            String preferredFirstHex,
            long userId
    ) {
        Objects.requireNonNull(targetGroup, "targetGroup");
        Objects.requireNonNull(garmentType, "garmentType");
        Objects.requireNonNull(qualityGrade, "qualityGrade");
        Objects.requireNonNull(packageType, "packageType");
        Objects.requireNonNull(location, "location");
        if (weightKg == null || weightKg.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Weight must be positive.");
        }
        if (quantity < 1 || quantity > 50) {
            throw new IllegalArgumentException("Batch quantity must be between 1 and 50.");
        }

        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        String cleanNotes = notes != null && !notes.isBlank() ? notes.trim() : null;

        List<Long> createdIds = db.executeInTransaction(conn -> {
            List<Long> ids = new ArrayList<>(quantity);
            Set<String> batchCodes = new HashSet<>();

            for (int i = 0; i < quantity; i++) {
                String qrCode;
                if (i == 0 && preferredFirstHex != null && preferredFirstHex.matches("^[0-9A-Fa-f]{2}$")) {
                    String candidate = codeService.buildCode(
                            now,
                            targetGroup.code(),
                            garmentType.code(),
                            qualityGrade.code(),
                            packageType.code(),
                            weightKg,
                            preferredFirstHex.toUpperCase()
                    );
                    if (!batchCodes.contains(candidate) && !packageDao.existsByQrCode(candidate)) {
                        qrCode = candidate;
                    } else {
                        qrCode = generateUniqueForBatch(now, targetGroup, garmentType, qualityGrade, packageType, weightKg, batchCodes);
                    }
                } else {
                    qrCode = generateUniqueForBatch(now, targetGroup, garmentType, qualityGrade, packageType, weightKg, batchCodes);
                }
                batchCodes.add(qrCode);
                String hex = qrCode.substring(qrCode.length() - 2);

                long id = packageDao.insert(
                        conn,
                        qrCode,
                        targetGroup.id(),
                        garmentType.id(),
                        qualityGrade.id(),
                        packageType.id(),
                        location.id(),
                        weightKg,
                        hex,
                        PackageStatus.IN_STOCK,
                        cleanNotes,
                        userId,
                        now
                );
                movementDao.insert(
                        conn,
                        id,
                        MovementType.INTAKE,
                        null,
                        location.id(),
                        userId,
                        cleanNotes != null ? cleanNotes : "Package intake",
                        now
                );
                ids.add(id);
            }
            return ids;
        });

        List<InventoryPackage> created = new ArrayList<>(createdIds.size());
        for (Long id : createdIds) {
            packageDao.findById(id).ifPresent(created::add);
        }
        LOG.info("Created {} inventory package(s) by userId={} at location {}", created.size(), userId, location.code());
        return created;
    }

    private String generateUniqueForBatch(
            LocalDateTime timestamp,
            TargetGroup targetGroup,
            GarmentType garmentType,
            QualityGrade qualityGrade,
            PackageType packageType,
            BigDecimal weightKg,
            Set<String> batchCodes
    ) {
        return codeService.generateUniqueCode(
                timestamp,
                targetGroup.code(),
                garmentType.code(),
                qualityGrade.code(),
                packageType.code(),
                weightKg,
                candidate -> batchCodes.contains(candidate) || packageDao.existsByQrCode(candidate)
        );
    }

    public InventoryPackage relocatePackage(long packageId, long newLocationId, String note, long userId) {
        InventoryPackage pkg = packageDao.findById(packageId)
                .orElseThrow(() -> new IllegalArgumentException("Package not found: " + packageId));
        if (pkg.status() == PackageStatus.SHIPPED || pkg.status() == PackageStatus.DISCARDED) {
            throw new IllegalStateException("Cannot relocate package with status: " + pkg.status());
        }
        LocalDateTime now = LocalDateTime.now();
        db.runInTransaction(conn -> {
            packageDao.updateLocation(conn, packageId, newLocationId, now);
            movementDao.insert(
                    conn,
                    packageId,
                    MovementType.RELOCATE,
                    pkg.locationId(),
                    newLocationId,
                    userId,
                    note != null && !note.isBlank() ? note.trim() : "Relocated via warehouse app",
                    now
            );
        });
        return packageDao.findById(packageId).orElseThrow();
    }

    public void verifyStocktake(long packageId, String note, long userId) {
        InventoryPackage pkg = packageDao.findById(packageId)
                .orElseThrow(() -> new IllegalArgumentException("Package not found: " + packageId));
        LocalDateTime now = LocalDateTime.now();
        db.runInTransaction(conn -> movementDao.insert(
                conn,
                packageId,
                MovementType.SCAN_VERIFY,
                pkg.locationId(),
                pkg.locationId(),
                userId,
                note != null && !note.isBlank() ? note.trim() : "Stocktake QR verification",
                now
        ));
    }

    public InventoryPackage discardPackage(long packageId, String note, long userId) {
        InventoryPackage pkg = packageDao.findById(packageId)
                .orElseThrow(() -> new IllegalArgumentException("Package not found: " + packageId));
        if (pkg.status() == PackageStatus.SHIPPED) {
            throw new IllegalStateException("Cannot discard an already shipped package.");
        }
        LocalDateTime now = LocalDateTime.now();
        db.runInTransaction(conn -> {
            packageDao.updateStatus(conn, packageId, PackageStatus.DISCARDED, now);
            movementDao.insert(
                    conn,
                    packageId,
                    MovementType.DISCARD,
                    pkg.locationId(),
                    null,
                    userId,
                    note != null && !note.isBlank() ? note.trim() : "Marked discarded",
                    now
            );
        });
        return packageDao.findById(packageId).orElseThrow();
    }

    /**
     * Updates an existing package's attributes, automatically recalculating the structured QR code
     * to match the naming convention while preserving the original entry timestamp.
     */
    public InventoryPackage updatePackageDetails(
            long packageId,
            TargetGroup targetGroup,
            GarmentType garmentType,
            QualityGrade qualityGrade,
            PackageType packageType,
            WarehouseLocation location,
            BigDecimal weightKg,
            PackageStatus status,
            String notes,
            long userId
    ) {
        Objects.requireNonNull(targetGroup, "targetGroup");
        Objects.requireNonNull(garmentType, "garmentType");
        Objects.requireNonNull(qualityGrade, "qualityGrade");
        Objects.requireNonNull(packageType, "packageType");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(status, "status");
        if (weightKg == null || weightKg.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Weight must be positive.");
        }

        InventoryPackage existing = packageDao.findById(packageId)
                .orElseThrow(() -> new IllegalArgumentException("Package not found: " + packageId));

        LocalDateTime entryTime = existing.createdAt() != null ? existing.createdAt() : LocalDateTime.now();
        String preferredQr = codeService.buildCode(
                entryTime,
                targetGroup.code(),
                garmentType.code(),
                qualityGrade.code(),
                packageType.code(),
                weightKg,
                existing.hexSuffix()
        );

        String finalQr;
        if (preferredQr.equalsIgnoreCase(existing.qrCode()) || !packageDao.existsByQrCode(preferredQr)) {
            finalQr = preferredQr;
        } else {
            finalQr = codeService.generateUniqueCode(
                    entryTime,
                    targetGroup.code(),
                    garmentType.code(),
                    qualityGrade.code(),
                    packageType.code(),
                    weightKg,
                    packageDao::existsByQrCode
            );
        }
        String finalHex = finalQr.substring(finalQr.length() - 2);
        String cleanNotes = notes != null && !notes.isBlank() ? notes.trim() : null;
        LocalDateTime now = LocalDateTime.now();

        boolean locationChanged = existing.locationId() != location.id();
        boolean codeChanged = !finalQr.equalsIgnoreCase(existing.qrCode());
        MovementType auditType = locationChanged ? MovementType.RELOCATE : MovementType.SCAN_VERIFY;
        String auditNote = codeChanged
                ? "Edited details (" + existing.qrCode() + " -> " + finalQr + ")"
                : (locationChanged ? "Edited details & relocated" : "Edited package details");

        db.runInTransaction(conn -> {
            packageDao.updateDetails(
                    conn, packageId, finalQr,
                    targetGroup.id(), garmentType.id(), qualityGrade.id(), packageType.id(),
                    location.id(), weightKg, finalHex, status, cleanNotes, now
            );
            movementDao.insert(conn, packageId, auditType, existing.locationId(), location.id(), userId, auditNote, now);
        });

        LOG.info("Updated package id={} ({} -> {}) by userId={}", packageId, existing.qrCode(), finalQr, userId);
        return packageDao.findById(packageId).orElseThrow();
    }
}
