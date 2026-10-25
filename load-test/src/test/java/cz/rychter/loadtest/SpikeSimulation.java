package cz.rychter.loadtest;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;

import io.gatling.javaapi.core.Simulation;
import java.time.Duration;

/**
 * Calm -> sudden burst -> calm. Shows how fast the service and its pool absorb a surge and how quickly
 * latency/heap/pool metrics recover. Tunables: -DbaseRate (20), -DspikeRate (500), -DspikeSec (30).
 */
public class SpikeSimulation extends Simulation {
    {
        int baseRate = TradeSupport.intProp("baseRate", 20);
        int spikeRate = TradeSupport.intProp("spikeRate", 500);
        int spikeSec = TradeSupport.intProp("spikeSec", 30);

        var mixed = scenario("spike").exec(TradeSupport.reportAndRead()).exec(TradeSupport.browse());

        setUp(mixed.injectOpen(
                constantUsersPerSec(baseRate).during(Duration.ofSeconds(60)),
                rampUsersPerSec(baseRate).to(spikeRate).during(Duration.ofSeconds(5)),
                constantUsersPerSec(spikeRate).during(Duration.ofSeconds(spikeSec)),
                rampUsersPerSec(spikeRate).to(baseRate).during(Duration.ofSeconds(5)),
                constantUsersPerSec(baseRate).during(Duration.ofSeconds(90))))
                .protocols(TradeSupport.protocol());
    }
}
