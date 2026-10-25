package cz.rychter.loadtest;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/** Shared protocol, payload generation and request chains for all trade simulations. */
final class TradeSupport {

    static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:18080");

    /** Makes transaction IDs unique across runs so the unique constraint on transaction_id is not hit by accident. */
    private static final String RUN_ID = Long.toString(System.currentTimeMillis(), 36);
    private static final AtomicLong SEQ = new AtomicLong();

    private TradeSupport() {
    }

    static int intProp(String name, int defaultValue) {
        return Integer.getInteger(name, defaultValue);
    }

    static HttpProtocolBuilder protocol() {
        return http.baseUrl(BASE_URL)
                .acceptHeader("application/json")
                .contentTypeHeader("application/json")
                .disableCaching();
    }

    static String payload(String transactionId) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String side = rnd.nextBoolean() ? "BUY" : "SELL";
        double quantity = rnd.nextInt(1, 10_000);
        double price = rnd.nextInt(100, 50_000) / 100.0;
        return """
                {"transactionId":"%s","isin":"US0378331005","ticker":"AAPL","side":"%s",
                 "quantity":%s,"price":%s,"currency":"USD","tradeTimestamp":"%s",
                 "brokerLei":"529900T8BM49AURSDO55","clientId":"CLIENT-%d","venueMic":"XNAS"}"""
                .formatted(transactionId, side, quantity, price, Instant.now(), rnd.nextInt(1, 500));
    }

    static String newTransactionId() {
        return "LT-" + RUN_ID + "-" + SEQ.incrementAndGet();
    }


    /** Write path (insert + unique check) followed by the two read-by-key paths. */
    static ChainBuilder reportAndRead() {
        return exec(session -> session.set("payload", payload(newTransactionId())))
                .exec(http("POST /trades")
                        .post("/api/v1/trades")
                        .body(StringBody("#{payload}"))
                        .check(status().is(201),
                                jsonPath("$.id").saveAs("tradeId"),
                                jsonPath("$.transactionId").saveAs("uti")))
                .exitHereIfFailed()
                .exec(http("GET /trades/{id}")
                        .get("/api/v1/trades/#{tradeId}")
                        .check(status().is(200)))
                .exec(http("GET /trades/tx/{uti}")
                        .get("/api/v1/trades/tx/#{uti}")
                        .check(status().is(200)));
    }

    /** Paged list; deeper pages cost more (OFFSET) as the table grows. */
    static ChainBuilder browse() {
        return exec(http("GET /trades?page")
                .get("/api/v1/trades")
                .queryParam("limit", "20")
                .queryParam("page", session -> String.valueOf(ThreadLocalRandom.current().nextInt(0, 50)))
                .check(status().is(200)));
    }
}
