package cz.rychter.trade;

import cz.rychter.trade.dto.TradeReportRequest;
import cz.rychter.trade.model.TradeSide;
import cz.rychter.trade.model.TradeStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;

/**
 * Integration Test (IT) verifying the full HTTP -> Panache -> PostgreSQL stack.
 * Executed during the Maven integration-test / verify lifecycle.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TradeTransactionResourceIT {

    private static final String UNIQUE_TX_ID = "TX-ISO20022-IT-" + UUID.randomUUID();

    @Test
    @Order(1)
    void testReportValidTradeAgainstDatabase() {
        TradeReportRequest request = new TradeReportRequest(
                UNIQUE_TX_ID,
                "US0378331005",
                "AAPL",
                TradeSide.BUY,
                new BigDecimal("150.0000"),
                new BigDecimal("178.5000"),
                "USD",
                Instant.parse("2026-10-24T14:30:00Z"),
                "5493006MHB84DD0ZWV18",
                "CLIENT-INST-9921",
                "XNAS",
                TradeStatus.NEW
        );

        Number tradeId = given()
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/api/v1/trades")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("transactionId", equalTo(UNIQUE_TX_ID))
                .body("isin", equalTo("US0378331005"))
                .body("ticker", equalTo("AAPL"))
                .body("side", equalTo("BUY"))
                .body("quantity", equalTo(150.0000f))
                .body("price", equalTo(178.5000f))
                .body("grossAmount", equalTo(26775.0000f))
                .body("currency", equalTo("USD"))
                .body("venueMic", equalTo("XNAS"))
                .body("status", equalTo("NEW"))
                .extract().path("id");

        given()
                .when()
                .get("/api/v1/trades/" + tradeId)
                .then()
                .statusCode(200)
                .body("transactionId", equalTo(UNIQUE_TX_ID))
                .body("ticker", equalTo("AAPL"));

        given()
                .when()
                .get("/api/v1/trades/tx/" + UNIQUE_TX_ID)
                .then()
                .statusCode(200)
                .body("id", equalTo(tradeId.intValue()))
                .body("ticker", equalTo("AAPL"));
    }

    @Test
    @Order(2)
    void testDuplicateTransactionIdReturnsConflictFromDatabase() {
        TradeReportRequest duplicateRequest = new TradeReportRequest(
                UNIQUE_TX_ID,
                "US0378331005",
                "AAPL",
                TradeSide.SELL,
                new BigDecimal("50.0000"),
                new BigDecimal("180.0000"),
                "USD",
                Instant.parse("2026-10-24T15:00:00Z"),
                "5493006MHB84DD0ZWV18",
                "CLIENT-INST-9921",
                "XNAS",
                TradeStatus.NEW
        );

        given()
                .contentType(ContentType.JSON)
                .body(duplicateRequest)
                .when()
                .post("/api/v1/trades")
                .then()
                .statusCode(409)
                .body("error", equalTo("Conflict"));
    }

    @Test
    @Order(3)
    void testValidationFailureOnInvalidISINAndNegativeQuantity() {
        TradeReportRequest invalidRequest = new TradeReportRequest(
                "TX-INVALID-IT-1",
                "INVALID_ISIN", // violates 12-char pattern
                "AAPL",
                TradeSide.BUY,
                new BigDecimal("-10.0000"), // invalid negative
                new BigDecimal("178.5000"),
                "USD",
                Instant.now(),
                "5493006MHB84DD0ZWV18",
                "CLIENT-INST-9921",
                "XNAS",
                TradeStatus.NEW
        );

        given()
                .contentType(ContentType.JSON)
                .body(invalidRequest)
                .when()
                .post("/api/v1/trades")
                .then()
                .statusCode(400);
    }
}
