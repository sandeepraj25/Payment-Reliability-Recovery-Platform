package com.payguard.simulation;

import com.payguard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

/**
 * Module H: every one of the 12 failure scenarios runs end to end through the real backend and must
 * end PASSED (all invariants held). If a mechanism breaks, the matching scenario fails here.
 */
class SimulationIT extends IntegrationTestBase {

    @ParameterizedTest(name = "{0}")
    @EnumSource(Scenario.class)
    void scenarioRunsAgainstTheRealBackendAndPasses(Scenario scenario) {
        long runId = ((Number) admin().post("/api/simulations/" + scenario.name() + "/runs")
                .then().statusCode(200).extract().path("runId")).longValue();

        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(500)).until(() ->
                !"RUNNING".equals(admin().get("/api/simulations/runs/" + runId).then().extract().path("status")));

        var run = admin().get("/api/simulations/runs/" + runId).then().statusCode(200).extract();
        String status = run.path("status");
        String invariants = run.path("invariants");
        assertThat(status).as("scenario %s: invariants=%s error=%s steps=%s", scenario, invariants,
                run.path("error"), run.path("steps")).isEqualTo("PASSED");
        assertThat((String) run.path("steps")).contains("\"INJECT\"");
        assertThat(invariants).contains("\"held\":true").doesNotContain("\"held\":false");
    }

    @Test
    void scenarioCatalogueAndUnknownScenario() {
        admin().get("/api/simulations/scenarios").then().statusCode(200).body("size()", equalTo(12));
        admin().post("/api/simulations/NOT_A_SCENARIO/runs").then().statusCode(404);
    }
}
