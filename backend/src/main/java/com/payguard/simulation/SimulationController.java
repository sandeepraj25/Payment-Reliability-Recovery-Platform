package com.payguard.simulation;

import com.payguard.common.error.ApiException;
import com.payguard.faults.FaultInjector;
import com.payguard.faults.FaultPoint;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Failure Simulation Lab API. The whole controller only exists when fault injection is enabled,
 * so in production these URLs do not exist at all (404), rather than being "protected".
 */
@RestController
// Needs BOTH: fault injection (demo) and the gateway simulator in this process (the API process, not the worker).
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${payguard.faults.enabled:false} and ${payguard.simulator.enabled:false}")
public class SimulationController {

    private final SimulationRunner runner;
    private final SimulationRecorder recorder;
    private final FaultInjector faults;

    public SimulationController(SimulationRunner runner, SimulationRecorder recorder, FaultInjector faults) {
        this.runner = runner;
        this.recorder = recorder;
        this.faults = faults;
    }

    public record ScenarioView(String id, String title, String explanation, String mechanisms) {
    }

    public record ArmRequest(@Min(1) @Max(100) int times) {
    }

    @GetMapping("/api/simulations/scenarios")
    public List<ScenarioView> scenarios() {
        return Arrays.stream(Scenario.values())
                .map(s -> new ScenarioView(s.name(), s.title(), s.explanation(), s.mechanisms())).toList();
    }

    @PostMapping("/api/simulations/{scenario}/runs")
    public Map<String, Object> run(@PathVariable String scenario, Principal principal) {
        Scenario s;
        try {
            s = Scenario.valueOf(scenario);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Scenario", scenario);
        }
        return Map.of("runId", runner.start(s, principal.getName()), "status", "RUNNING");
    }

    @GetMapping("/api/simulations/runs")
    public List<SimulationRecorder.Run> runs(@RequestParam(required = false) String scenario,
                                             @RequestParam(defaultValue = "30") int limit) {
        return recorder.recent(scenario, Math.max(1, Math.min(limit, 200)));
    }

    @GetMapping("/api/simulations/runs/{id}")
    public SimulationRecorder.Run run(@PathVariable long id) {
        return recorder.get(id).orElseThrow(() -> ApiException.notFound("Simulation run", id));
    }

    // Low-level fault switches (for exploring by hand).

    @GetMapping("/api/faults")
    public Map<String, Object> faults() {
        return Map.of("points", FaultPoint.values(), "armed", faults.armed());
    }

    @PostMapping("/api/faults/{point}")
    public void arm(@PathVariable FaultPoint point, @jakarta.validation.Valid @RequestBody ArmRequest request, Principal principal) {
        faults.arm(point, request.times(), principal.getName());
    }

    @DeleteMapping("/api/faults")
    public void disarmAll() {
        faults.disarmAll();
    }
}
