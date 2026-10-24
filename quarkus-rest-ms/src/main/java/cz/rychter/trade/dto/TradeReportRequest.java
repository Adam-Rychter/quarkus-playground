package cz.rychter.trade.dto;

import cz.rychter.trade.model.TradeSide;
import cz.rychter.trade.model.TradeStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public record TradeReportRequest(
        @NotBlank(message = "Transaction ID cannot be blank")
        @Size(max = 64, message = "Transaction ID cannot exceed 64 characters")
        String transactionId,

        @NotBlank(message = "ISIN cannot be blank")
        @Pattern(regexp = "^[A-Z]{2}[A-Z0-9]{9}[0-9]$", message = "Invalid ISIN format (e.g., US0378331005)")
        String isin,

        @NotBlank(message = "Ticker cannot be blank")
        @Size(max = 16, message = "Ticker cannot exceed 16 characters")
        String ticker,

        @NotNull(message = "Trade side (BUY/SELL) is required")
        TradeSide side,

        @NotNull(message = "Quantity is required")
        @DecimalMin(value = "0.0001", message = "Quantity must be greater than zero")
        BigDecimal quantity,

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.0001", message = "Price must be greater than zero")
        BigDecimal price,

        @NotBlank(message = "Currency cannot be blank")
        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code (e.g., USD, EUR)")
        String currency,

        @NotNull(message = "Trade timestamp is required")
        Instant tradeTimestamp,

        @NotBlank(message = "Broker LEI cannot be blank")
        @Pattern(regexp = "^[A-Z0-9]{20}$", message = "Broker LEI must be a 20-character alphanumeric ISO 17442 code")
        String brokerLei,

        @NotBlank(message = "Client ID cannot be blank")
        @Size(max = 64, message = "Client ID cannot exceed 64 characters")
        String clientId,

        @NotBlank(message = "Venue MIC cannot be blank")
        @Pattern(regexp = "^[A-Z0-9]{4}$", message = "Venue MIC must be a 4-character ISO 10383 code (e.g., XNAS)")
        String venueMic,

        TradeStatus status
) {
}
