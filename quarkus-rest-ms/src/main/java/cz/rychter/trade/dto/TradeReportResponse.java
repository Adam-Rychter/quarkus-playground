package cz.rychter.trade.dto;

import cz.rychter.trade.model.TradeSide;
import cz.rychter.trade.model.TradeStatus;
import cz.rychter.trade.model.TradeTransaction;
import java.math.BigDecimal;
import java.time.Instant;

public record TradeReportResponse(
        Long id,
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
    public static TradeReportResponse from(TradeTransaction entity) {
        return new TradeReportResponse(
                entity.getId(),
                entity.getTransactionId(),
                entity.getIsin(),
                entity.getTicker(),
                entity.getSide(),
                entity.getQuantity(),
                entity.getPrice(),
                entity.getCurrency(),
                entity.getGrossAmount(),
                entity.getTradeTimestamp(),
                entity.getBrokerLei(),
                entity.getClientId(),
                entity.getVenueMic(),
                entity.getStatus(),
                entity.getCreatedAt()
        );
    }
}
