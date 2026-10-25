package cz.rychter.loadtest;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;

import io.gatling.javaapi.core.Simulation;
import java.time.Duration;

/**
 * Baseline: steady, realistic mix (writers + readers) at a rate the service should handle comfortably.
 * Tunables: -Drate (new users/s, default 50), -DdurationSec (default 120), -DwarmupSec (default 30).
 */
public class LoadSimulation extends Simulation {
    {
        int rate = TradeSupport.intProp("rate", 50);
        int duration = TradeSupport.intProp("durationSec", 120);
        int warmup = TradeSupport.intProp("warmupSec", 30);

        var ingest = scenario("ingest: POST + 2 reads").exec(TradeSupport.reportAndRead());
        var browse = scenario("browse: paged list").exec(TradeSupport.browse());

        setUp(
                ingest.injectOpen(
                        rampUsersPerSec(1).to(rate).during(Duration.ofSeconds(warmup)),
                        constantUsersPerSec(rate).during(Duration.ofSeconds(duration))),
                browse.injectOpen(
                        rampUsersPerSec(1).to(Math.max(1, rate / 2)).during(Duration.ofSeconds(warmup)),
                        constantUsersPerSec(Math.max(1, rate / 2)).during(Duration.ofSeconds(duration))))
                .protocols(TradeSupport.protocol())
                .assertions(
                        global().failedRequests().percent().lt(1.0),
                        global().responseTime().percentile(95.0).lt(1000));
    }
}
