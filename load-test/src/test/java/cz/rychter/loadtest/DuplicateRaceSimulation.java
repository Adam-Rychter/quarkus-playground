package cz.rychter.loadtest;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.Simulation;

/**
 * Correctness under concurrency: many clients POST the SAME transactionId at the same time.
 * The resource does check-then-insert, so exactly one 201 is expected, the rest 409. Any 500
 * (unique-constraint violation leaking from the DB) shows up as a KO "status.find.in(201,409)" failure.
 * Tunables: -Dusers (default 50), -Drounds (default 40).
 */
public class DuplicateRaceSimulation extends Simulation {
    private static final String RACE_PREFIX = "RACE-" + TradeSupport.newTransactionId() + "-";

    {
        int users = TradeSupport.intProp("users", 50);
        int rounds = TradeSupport.intProp("rounds", 40);

        var race = scenario("duplicate UTI race")
                .repeat(rounds, "round").on(
                        exec(session -> session.set("payload",
                                TradeSupport.payload(RACE_PREFIX + session.getInt("round"))))
                                .exec(http("POST duplicate UTI")
                                        .post("/api/v1/trades")
                                        .body(StringBody("#{payload}"))
                                        .check(status().in(201, 409))));

        setUp(race.injectOpen(atOnceUsers(users))).protocols(TradeSupport.protocol());
    }
}
