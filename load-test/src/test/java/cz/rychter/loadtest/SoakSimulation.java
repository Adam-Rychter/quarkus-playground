package cz.rychter.loadtest;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;

import io.gatling.javaapi.core.Simulation;
import java.time.Duration;

/**
 * Long, moderate load to expose slow leaks (heap, threads, connections) and GC drift.
 * Tunables: -Drate (default 30 users/s), -DdurationMin (default 30).
 */
public class SoakSimulation extends Simulation {
    {
        int rate = TradeSupport.intProp("rate", 30);
        int minutes = TradeSupport.intProp("durationMin", 30);

        var mixed = scenario("soak").exec(TradeSupport.reportAndRead()).exec(TradeSupport.browse());

        setUp(mixed.injectOpen(constantUsersPerSec(rate).during(Duration.ofMinutes(minutes))))
                .protocols(TradeSupport.protocol())
                .assertions(global().failedRequests().percent().lt(1.0));
    }
}
