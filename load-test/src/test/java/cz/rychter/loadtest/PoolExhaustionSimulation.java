package cz.rychter.loadtest;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;

import io.gatling.javaapi.core.Simulation;
import java.time.Duration;

/**
 * Closed model: N concurrent clients hammer the service back-to-back without think time, so in-flight
 * requests are bounded only by N. With N far above the Agroal pool size (quarkus.datasource.jdbc.max-size)
 * requests queue for a connection and, after acquisition-timeout (5s), fail. Run it with a small pool
 * (POOL_MAX=5 in loadtest.sh) to make exhaustion obvious.
 * Tunables: -Dusers (default 200), -DrampSec (30), -DholdSec (90).
 */
public class PoolExhaustionSimulation extends Simulation {
    {
        int users = TradeSupport.intProp("users", 200);
        int ramp = TradeSupport.intProp("rampSec", 30);
        int hold = TradeSupport.intProp("holdSec", 90);

        var hammer = scenario("pool exhaustion")
                .during(Duration.ofSeconds(ramp + hold)).on(
                        exec(TradeSupport.reportAndRead())
                                .exec(TradeSupport.browse()));

        setUp(hammer.injectClosed(
                rampConcurrentUsers(10).to(users).during(Duration.ofSeconds(ramp)),
                constantConcurrentUsers(users).during(Duration.ofSeconds(hold))))
                .protocols(TradeSupport.protocol());
    }
}
