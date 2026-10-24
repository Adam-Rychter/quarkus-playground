package cz.rychter.trade.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "trade_transactions",
        indexes = {
                @Index(name = "idx_trade_tx_id", columnList = "transaction_id", unique = true),
                @Index(name = "idx_trade_isin", columnList = "isin"),
                @Index(name = "idx_trade_ticker", columnList = "ticker"),
                @Index(name = "idx_trade_timestamp", columnList = "trade_timestamp")
        }
)
public class TradeTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, unique = true, length = 64)
    private String transactionId;

    @Column(name = "isin", nullable = false, length = 12)
    private String isin;

    @Column(name = "ticker", nullable = false, length = 16)
    private String ticker;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 8)
    private TradeSide side;

    @Column(name = "quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(name = "price", nullable = false, precision = 18, scale = 4)
    private BigDecimal price;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "gross_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal grossAmount;

    @Column(name = "trade_timestamp", nullable = false)
    private Instant tradeTimestamp;

    @Column(name = "broker_lei", nullable = false, length = 20)
    private String brokerLei;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "venue_mic", nullable = false, length = 4)
    private String venueMic;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TradeStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public TradeTransaction() {
    }

    public TradeTransaction(
            String transactionId,
            String isin,
            String ticker,
            TradeSide side,
            BigDecimal quantity,
            BigDecimal price,
            String currency,
            BigDecimal grossAmount,
            Instant tradeTimestamp,
            String brokerLei,
            String clientId,
            String venueMic,
            TradeStatus status,
            Instant createdAt
    ) {
        this.transactionId = transactionId;
        this.isin = isin;
        this.ticker = ticker;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.currency = currency;
        this.grossAmount = grossAmount;
        this.tradeTimestamp = tradeTimestamp;
        this.brokerLei = brokerLei;
        this.clientId = clientId;
        this.venueMic = venueMic;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getIsin() {
        return isin;
    }

    public void setIsin(String isin) {
        this.isin = isin;
    }

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public TradeSide getSide() {
        return side;
    }

    public void setSide(TradeSide side) {
        this.side = side;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public void setGrossAmount(BigDecimal grossAmount) {
        this.grossAmount = grossAmount;
    }

    public Instant getTradeTimestamp() {
        return tradeTimestamp;
    }

    public void setTradeTimestamp(Instant tradeTimestamp) {
        this.tradeTimestamp = tradeTimestamp;
    }

    public String getBrokerLei() {
        return brokerLei;
    }

    public void setBrokerLei(String brokerLei) {
        this.brokerLei = brokerLei;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getVenueMic() {
        return venueMic;
    }

    public void setVenueMic(String venueMic) {
        this.venueMic = venueMic;
    }

    public TradeStatus getStatus() {
        return status;
    }

    public void setStatus(TradeStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
