package cz.rychter.loadtest;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;

import io.gatling.javaapi.core.Simulation;
import java.time.Duration;

/**
 * Open-model ramp that keeps increasing the arrival rate regardless of how the service copes,
 * to find the saturation point (latency knee, first errors).
 * Tunables: -DmaxRate (default 600 users/s), -DdurationSec (ramp, default 240), -DholdSec (default 60).
 */
public class StressSimulation extends Simulation {
    {
        int maxRate = TradeSupport.intProp("maxRate", 600);
        int ramp = TradeSupport.intProp("durationSec", 240);
        int hold = TradeSupport.intProp("holdSec", 60);

        var mixed = scenario("stress: write + read + list")
                .exec(TradeSupport.reportAndRead())
                .exec(TradeSupport.browse());

        setUp(mixed.injectOpen(
                rampUsersPerSec(10).to(maxRate).during(Duration.ofSeconds(ramp)),
                constantUsersPerSec(maxRate).during(Duration.ofSeconds(hold))))
                .protocols(TradeSupport.protocol());
    }
}
