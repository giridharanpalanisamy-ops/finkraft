package com.finkraft.handler.invoice;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InvoiceRepository extends JpaRepository<InvoiceEntity, UUID> {

    Optional<InvoiceEntity> findBySellerIdAndInvoiceNumber(UUID sellerId, String invoiceNumber);

    @Query(value = """
            SELECT id FROM invoices
            WHERE seller_id = :sellerId AND content_hash = :hash AND invoice_number <> :invoiceNumber
            ORDER BY created_at LIMIT 1""", nativeQuery = true)
    Optional<UUID> findOtherWithSameHash(UUID sellerId, String hash, String invoiceNumber);
}
