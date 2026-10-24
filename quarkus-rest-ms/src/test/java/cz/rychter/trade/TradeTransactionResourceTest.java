package cz.rychter.trade;

import cz.rychter.trade.dto.TradeReportRequest;
import cz.rychter.trade.dto.TradeReportResponse;
import cz.rychter.trade.model.TradeSide;
import cz.rychter.trade.model.TradeStatus;
import cz.rychter.trade.model.TradeTransaction;
import cz.rychter.trade.repository.TradeTransactionRepository;
import cz.rychter.trade.resource.TradeTransactionResource;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Unit Test verifying TradeTransactionResource logic in isolation.
 * Uses Mockito to mock repository dependencies. Does NOT require running Quarkus or PostgreSQL.
 * Executed during the Maven 'test' lifecycle phase.
 */
@ExtendWith(MockitoExtension.class)
class TradeTransactionResourceTest {

    @Mock
    private TradeTransactionRepository tradeRepository;

    private TradeTransactionResource resource;

    @BeforeEach
    void setUp() {
        resource = new TradeTransactionResource(tradeRepository);
    }

    @Test
    void testReportTradeSuccess() {
        String txId = "TX-UNIT-001";
        TradeReportRequest request = new TradeReportRequest(
                txId,
                "US0378331005",
                "aapl",
                TradeSide.BUY,
                new BigDecimal("100.0000"),
                new BigDecimal("150.0000"),
                "usd",
                Instant.parse("2026-10-24T10:00:00Z"),
                "5493006mhb84dd0zwv18",
                "CLIENT-42",
                "xnas",
                TradeStatus.NEW
        );

        when(tradeRepository.findByTransactionId(txId)).thenReturn(Optional.empty());

        Response response = resource.reportTrade(request);

        assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
        TradeReportResponse body = (TradeReportResponse) response.getEntity();
        assertNotNull(body);
        assertEquals(txId, body.transactionId());
        assertEquals("AAPL", body.ticker(), "Ticker should be normalized to uppercase");
        assertEquals("USD", body.currency(), "Currency should be normalized to uppercase");
        assertEquals("XNAS", body.venueMic(), "Venue MIC should be normalized to uppercase");
        assertEquals("5493006MHB84DD0ZWV18", body.brokerLei(), "LEI should be normalized to uppercase");
        assertEquals(new BigDecimal("15000.00000000"), body.grossAmount(), "Gross amount must be quantity * price");

        ArgumentCaptor<TradeTransaction> entityCaptor = ArgumentCaptor.forClass(TradeTransaction.class);
        verify(tradeRepository, times(1)).persist(entityCaptor.capture());
        assertEquals(txId, entityCaptor.getValue().getTransactionId());
    }

    @Test
    void testReportTradeConflictWhenTransactionIdExists() {
        String txId = "TX-EXISTING-001";
        TradeReportRequest request = new TradeReportRequest(
                txId,
                "US0378331005",
                "AAPL",
                TradeSide.BUY,
                new BigDecimal("10.0000"),
                new BigDecimal("150.0000"),
                "USD",
                Instant.now(),
                "5493006MHB84DD0ZWV18",
                "CLIENT-42",
                "XNAS",
                TradeStatus.NEW
        );

        when(tradeRepository.findByTransactionId(txId)).thenReturn(Optional.of(new TradeTransaction()));

        Response response = resource.reportTrade(request);

        assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals("Conflict", body.get("error"));
        verify(tradeRepository, never()).persist(any(TradeTransaction.class));
    }

    @Test
    void testGetTradeByIdFound() {
        Long tradeId = 42L;
        TradeTransaction mockEntity = new TradeTransaction(
                "TX-42",
                "US0378331005",
                "AAPL",
                TradeSide.BUY,
                new BigDecimal("10.0000"),
                new BigDecimal("100.0000"),
                "USD",
                new BigDecimal("1000.0000"),
                Instant.now(),
                "5493006MHB84DD0ZWV18",
                "CLIENT-42",
                "XNAS",
                TradeStatus.NEW,
                Instant.now()
        );
        mockEntity.setId(tradeId);

        when(tradeRepository.findByIdOptional(tradeId)).thenReturn(Optional.of(mockEntity));

        Response response = resource.getTradeById(tradeId);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        TradeReportResponse body = (TradeReportResponse) response.getEntity();
        assertEquals("TX-42", body.transactionId());
        assertEquals(tradeId, body.id());
    }

    @Test
    void testGetTradeByIdNotFound() {
        Long tradeId = 999L;
        when(tradeRepository.findByIdOptional(tradeId)).thenReturn(Optional.empty());

        Response response = resource.getTradeById(tradeId);

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    void testGetTradeByTransactionIdNotFound() {
        String missingTx = "TX-MISSING";
        when(tradeRepository.findByTransactionId(missingTx)).thenReturn(Optional.empty());

        Response response = resource.getTradeByTransactionId(missingTx);

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }
}
