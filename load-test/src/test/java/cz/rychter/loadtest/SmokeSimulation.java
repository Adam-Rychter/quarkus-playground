package cz.rychter.loadtest;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;

import io.gatling.javaapi.core.Simulation;

/** Validates the scripts and the environment with a handful of requests. */
public class SmokeSimulation extends Simulation {
    {
        setUp(scenario("smoke").exec(TradeSupport.reportAndRead()).exec(TradeSupport.browse())
                .injectOpen(atOnceUsers(5)))
                .protocols(TradeSupport.protocol())
                .assertions(global().failedRequests().count().is(0L));
    }
}
