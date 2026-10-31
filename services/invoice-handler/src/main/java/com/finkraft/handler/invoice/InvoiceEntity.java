package com.finkraft.handler.invoice;

import com.finkraft.canonical.InvoiceState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/** A row of invoices. Only the InvoiceHandler maps it, and only the InvoiceHandler writes it (R3). */
@Entity
@Table(name = "invoices")
public class InvoiceEntity {

    @Id
    private UUID id;

    private UUID tenantId;
    private UUID sellerId;
    private String invoiceNumber;
    private int revision;
    private String contentHash;

    /** Canonical invoice JSON; stored as jsonb. */
    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String canonical;

    @Enumerated(EnumType.STRING)
    private InvoiceState state;

    /** Optimistic lock: every update runs WHERE version = ? (R8). */
    @Version
    private long version;

    private String aspId;
    private String aspRef;
    private UUID clientRef;
    private int attemptCount;
    private int pollCount;
    private Instant nextAttemptAt;
    private Instant leaseExpiresAt;
    private Instant deadlineAt;
    private Instant deliveredAt;
    private Instant reportedAt;
    private UUID possibleDuplicateOf;
    private Instant createdAt;
    private Instant updatedAt;

    protected InvoiceEntity() {}

    public InvoiceEntity(UUID id, UUID tenantId, UUID sellerId, String invoiceNumber, String contentHash,
            String canonical, Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.sellerId = sellerId;
        this.invoiceNumber = invoiceNumber;
        this.revision = 1;
        this.contentHash = contentHash;
        this.canonical = canonical;
        this.state = InvoiceState.RECEIVED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Clears everything tied to a previous submission when a corrected revision arrives. */
    public void startRevision(int revision, String contentHash, String canonical) {
        this.revision = revision;
        this.contentHash = contentHash;
        this.canonical = canonical;
        this.aspId = null;
        this.aspRef = null;
        this.clientRef = null;
        this.attemptCount = 0;
        this.pollCount = 0;
        this.nextAttemptAt = null;
        this.leaseExpiresAt = null;
        this.deadlineAt = null;
        this.deliveredAt = null;
        this.reportedAt = null;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getSellerId() { return sellerId; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public int getRevision() { return revision; }
    public String getContentHash() { return contentHash; }
    public String getCanonical() { return canonical; }
    public InvoiceState getState() { return state; }
    public long getVersion() { return version; }
    public String getAspId() { return aspId; }
    public String getAspRef() { return aspRef; }
    public UUID getClientRef() { return clientRef; }
    public int getAttemptCount() { return attemptCount; }
    public int getPollCount() { return pollCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getReportedAt() { return reportedAt; }
    public UUID getPossibleDuplicateOf() { return possibleDuplicateOf; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setState(InvoiceState state) { this.state = state; }
    public void setAspId(String aspId) { this.aspId = aspId; }
    public void setAspRef(String aspRef) { this.aspRef = aspRef; }
    public void setClientRef(UUID clientRef) { this.clientRef = clientRef; }
    public void setPollCount(int pollCount) { this.pollCount = pollCount; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public void setDeadlineAt(Instant deadlineAt) { this.deadlineAt = deadlineAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
    public void setReportedAt(Instant reportedAt) { this.reportedAt = reportedAt; }
    public void setPossibleDuplicateOf(UUID possibleDuplicateOf) { this.possibleDuplicateOf = possibleDuplicateOf; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
