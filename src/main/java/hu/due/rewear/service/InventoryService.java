package hu.due.rewear.service;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.dao.DaoException;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.StockMovementDao;
import hu.due.rewear.model.GarmentType;
import hu.due.rewear.model.InventoryPackage;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.MovementType;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.PackageType;
import hu.due.rewear.model.QualityGrade;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.TargetGroup;
import hu.due.rewear.model.WarehouseLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;

/**
 * Coordinates transactional package intake, relocation, stocktake verification, and disposal.
 */
public final class InventoryService {

    private static final Logger LOG = LoggerFactory.getLogger(InventoryService.class);

    private final DatabaseManager db;
    private final InventoryPackageDao packageDao;
    private final StockMovementDao movementDao;
    private final ProductCodeService codeService;
    private NotificationService notificationService;

    public InventoryService(DatabaseManager db,
                            InventoryPackageDao packageDao,
                            StockMovementDao movementDao,
                            ProductCodeService codeService) {
        this.db = db;
        this.packageDao = packageDao;
        this.movementDao = movementDao;
        this.codeService = codeService;
    }

    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Generates unsaved preview {@link InventoryPackage} instances (with collision-checked QR codes)
     * so the operator/manager can visually inspect and print-preview all labels BEFORE committing to SQLite.
     * Operators are strictly restricted to {@link LocationType#OPERATOR_STATION} workstations.
     */
    public List<InventoryPackage> buildPreviewPackages(
            TargetGroup targetGroup,
            GarmentType garmentType,
            QualityGrade qualityGrade,
            PackageType packageType,
            WarehouseLocation location,
            BigDecimal weightKg,
            int quantity,
            String notes,
            String preferredFirstHex,
            long userId,
            RoleCode actorRole
    ) {
        Objects.requireNonNull(targetGroup, "targetGroup");
        Objects.requireNonNull(garmentType, "garmentType");
        Objects.requireNonNull(qualityGrade, "qualityGrade");
        Objects.requireNonNull(packageType, "packageType");
        Objects.requireNonNull(location, "location");
        if (actorRole == RoleCode.OPERATOR && location.locationType() != LocationType.OPERATOR_STATION) {
            throw new IllegalArgumentException("Operators can only place new packages at an OPERATOR_STATION.");
        }
        if (weightKg == null || weightKg.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Weight must be positive.");
        }
        if (quantity < 1 || quantity > 50) {
            throw new IllegalArgumentException("Batch quantity must be between 1 and 50.");
        }

        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        String cleanNotes = notes != null && !notes.isBlank() ? notes.trim() : null;
        List<InventoryPackage> previews = new ArrayList<>(quantity);
        Set<String> batchCodes = new HashSet<>();

        for (int i = 0; i < quantity; i++) {
            String qrCode;
            if (i == 0 && preferredFirstHex != null && preferredFirstHex.matches("^[0-9A-Fa-f]{2}$")) {
                String candidate = codeService.buildCode(
                        now, targetGroup.code(), garmentType.code(), qualityGrade.code(),
                        packageType.code(), weightKg, preferredFirstHex.toUpperCase(Locale.ROOT));
                if (!batchCodes.contains(candidate) && !packageDao.existsByQrCode(candidate)) {
                    qrCode = candidate;
                } else {
                    qrCode = codeService.generateUniqueCode(now, targetGroup.code(), garmentType.code(),
                            qualityGrade.code(), packageType.code(), weightKg,
                            c -> batchCodes.contains(c) || packageDao.existsByQrCode(c));
                }
            } else {
                qrCode = codeService.generateUniqueCode(now, targetGroup.code(), garmentType.code(),
                        qualityGrade.code(), packageType.code(), weightKg,
                        c -> batchCodes.contains(c) || packageDao.existsByQrCode(c));
            }
            batchCodes.add(qrCode);
            String hex = qrCode.substring(qrCode.length() - 2);
            previews.add(new InventoryPackage(
                    -1L - i,
                    qrCode,
                    targetGroup.id(), targetGroup.code(), targetGroup.nameEn(), targetGroup.nameHu(),
                    garmentType.id(), garmentType.code(), garmentType.nameEn(), garmentType.nameHu(),
                    garmentType.priceEurPerKg(), garmentType.priceHufPerKg(),
                    qualityGrade.id(), qualityGrade.code(), qualityGrade.nameEn(), qualityGrade.nameHu(),
                    qualityGrade.defaultPricePerKg(), qualityGrade.defaultPriceHufPerKg(),
                    packageType.id(), packageType.code(), packageType.nameEn(), packageType.nameHu(),
                    location.id(), location.code(),
                    weightKg, hex, PackageStatus.IN_STOCK, cleanNotes, userId, "", "", now, now
            ));
        }
        return previews;
    }

    /**
     * Atomically commits previously previewed packages to SQLite and writes {@link MovementType#INTAKE}
     * audit entries. Enforces {@link LocationType#OPERATOR_STATION} for {@link RoleCode#OPERATOR}.
     */
    public List<InventoryPackage> commitPreviewedPackages(List<InventoryPackage> previews,
                                                          WarehouseLocation location,
                                                          long userId,
                                                          RoleCode actorRole) {
        if (previews == null || previews.isEmpty()) {
            throw new IllegalArgumentException("At least one preview package is required.");
        }
        Objects.requireNonNull(location, "location");
        if (actorRole == RoleCode.OPERATOR && location.locationType() != LocationType.OPERATOR_STATION) {
            throw new IllegalArgumentException("Operators can only place new packages at an OPERATOR_STATION.");
        }
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        List<Long> createdIds = db.executeInTransaction(conn -> {
            List<Long> ids = new ArrayList<>(previews.size());
            Set<String> batchCodes = new HashSet<>();
            for (InventoryPackage p : previews) {
                String qr = p.qrCode();
                if (batchCodes.contains(qr) || packageDao.existsByQrCode(conn, qr)) {
                    qr = codeService.generateUniqueCode(now, p.targetGroupCode(), p.garmentTypeCode(),
                            p.qualityGradeCode(), p.packageTypeCode(), p.weightKg(),
                            c -> {
                                try {
                                    return batchCodes.contains(c) || packageDao.existsByQrCode(conn, c);
                                } catch (SQLException e) {
                                    throw new DaoException("Failed checking QR uniqueness", e);
                                }
                            });
                }
                batchCodes.add(qr);
                String hex = qr.substring(qr.length() - 2);
                long id = packageDao.insert(conn, qr,
                        p.targetGroupId(), p.garmentTypeId(), p.qualityGradeId(), p.packageTypeId(),
                        location.id(), p.weightKg(), hex, PackageStatus.IN_STOCK, p.notes(), userId, now);
                movementDao.insert(conn, id, MovementType.INTAKE, null, location.id(), userId,
                        p.notes() != null ? p.notes() : "Initial package intake", now);
                ids.add(id);
            }
            return ids;
        });

        List<InventoryPackage> saved = new ArrayList<>(createdIds.size());
        for (Long id : createdIds) {
            packageDao.findById(id).ifPresent(saved::add);
        }
        LOG.info("Committed {} previewed inventory package(s) by userId={} at location {}",
                saved.size(), userId, location.code());
        if (notificationService != null && location.locationType() == LocationType.OPERATOR_STATION && !saved.isEmpty()) {
            notificationService.notifyRoles(
                    java.util.Set.of(RoleCode.FORKLIFT),
                    hu.due.rewear.model.AppNotification.CAT_PUTAWAY,
                    "Putaway Pickup: " + location.code(),
                    saved.size() + " package(s) created at " + location.code() + " (" + saved.get(0).qrCode() + ") ready for shelf putaway.",
                    hu.due.rewear.ui.MainFrame.VIEW_TRANSPORT);
        }
        return saved;
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
                            preferredFirstHex.toUpperCase(Locale.ROOT)
                    );
                    if (!batchCodes.contains(candidate) && !packageDao.existsByQrCode(conn, candidate)) {
                        qrCode = candidate;
                    } else {
                        qrCode = generateUniqueForBatch(conn, now, targetGroup, garmentType, qualityGrade, packageType, weightKg, batchCodes);
                    }
                } else {
                    qrCode = generateUniqueForBatch(conn, now, targetGroup, garmentType, qualityGrade, packageType, weightKg, batchCodes);
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
            Connection conn,
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
                candidate -> batchCodes.contains(candidate) || existsInTransaction(conn, candidate)
        );
    }

    private boolean existsInTransaction(Connection conn, String qrCode) {
        try {
            return packageDao.existsByQrCode(conn, qrCode);
        } catch (SQLException e) {
            throw new DaoException("Failed to verify QR uniqueness in transaction: " + qrCode, e);
        }
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

    /**
     * Atomically relocates multiple selected packages to {@code newLocationId} inside a single
     * SQLite transaction, rejecting the batch if any package is already {@code SHIPPED} or {@code DISCARDED}.
     */
    public int relocatePackages(List<Long> packageIds, long newLocationId, String note, long userId) {
        if (packageIds == null || packageIds.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        String auditNote = note != null && !note.isBlank() ? note.trim() : "Bulk relocated via warehouse app";
        return db.executeInTransaction(conn -> {
            int moved = 0;
            for (Long packageId : packageIds) {
                if (packageId == null) continue;
                InventoryPackage pkg = packageDao.findById(conn, packageId)
                        .orElseThrow(() -> new IllegalArgumentException("Package not found: " + packageId));
                if (pkg.status() == PackageStatus.SHIPPED || pkg.status() == PackageStatus.DISCARDED) {
                    throw new IllegalStateException("Cannot relocate package " + pkg.qrCode()
                            + " with status: " + pkg.status());
                }
                if (pkg.locationId() == newLocationId) {
                    continue;
                }
                packageDao.updateLocation(conn, packageId, newLocationId, now);
                movementDao.insert(conn, packageId, MovementType.RELOCATE,
                        pkg.locationId(), newLocationId, userId, auditNote, now);
                moved++;
            }
            return moved;
        });
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

        String cleanNotes = notes != null && !notes.isBlank() ? notes.trim() : null;
        LocalDateTime now = LocalDateTime.now();

        String finalQr = db.executeInTransaction(conn -> {
            InventoryPackage existing = packageDao.findById(conn, packageId)
                    .orElseThrow(() -> new IllegalArgumentException("Package not found: " + packageId));

            LocalDateTime entryTime = existing.createdAt() != null ? existing.createdAt() : now;
            String preferredQr = codeService.buildCode(
                    entryTime,
                    targetGroup.code(),
                    garmentType.code(),
                    qualityGrade.code(),
                    packageType.code(),
                    weightKg,
                    existing.hexSuffix()
            );

            String resolvedQr;
            if (preferredQr.equalsIgnoreCase(existing.qrCode()) || !packageDao.existsByQrCode(conn, preferredQr)) {
                resolvedQr = preferredQr;
            } else {
                resolvedQr = codeService.generateUniqueCode(
                        entryTime,
                        targetGroup.code(),
                        garmentType.code(),
                        qualityGrade.code(),
                        packageType.code(),
                        weightKg,
                        candidate -> existsInTransaction(conn, candidate)
                );
            }
            String finalHex = resolvedQr.substring(resolvedQr.length() - 2);

            boolean locationChanged = existing.locationId() != location.id();
            boolean codeChanged = !resolvedQr.equalsIgnoreCase(existing.qrCode());
            MovementType auditType = locationChanged ? MovementType.RELOCATE : MovementType.SCAN_VERIFY;
            String auditNote = codeChanged
                    ? "Edited details (" + existing.qrCode() + " -> " + resolvedQr + ")"
                    : (locationChanged ? "Edited details & relocated" : "Edited package details");

            packageDao.updateDetails(
                    conn, packageId, resolvedQr,
                    targetGroup.id(), garmentType.id(), qualityGrade.id(), packageType.id(),
                    location.id(), weightKg, finalHex, status, cleanNotes, now
            );
            movementDao.insert(conn, packageId, auditType, existing.locationId(), location.id(), userId, auditNote, now);
            return resolvedQr;
        });

        LOG.info("Updated package id={} (qr={}) by userId={}", packageId, finalQr, userId);
        return packageDao.findById(packageId).orElseThrow();
    }
}
