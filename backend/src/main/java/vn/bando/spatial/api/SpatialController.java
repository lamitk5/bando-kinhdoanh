package vn.bando.spatial.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.bando.spatial.api.SpatialModels.HealthResponse;
import vn.bando.spatial.api.SpatialModels.SummaryResponse;
import vn.bando.spatial.api.SpatialService.ListOfSamples;

@RestController
@RequestMapping("/api/spatial")
public class SpatialController {

    private final SpatialService service;

    public SpatialController(SpatialService service) {
        this.service = service;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        HealthResponse health = service.health();
        boolean ready = health.gistOk()
                && health.districtCount() == 4
                && health.latestImportId() != null
                && "PONG".equals(health.redis());
        return ResponseEntity.status(ready ? 200 : 503).body(health);
    }

    @GetMapping("/summary")
    public SummaryResponse summary() {
        return service.summary();
    }

    @GetMapping("/samples")
    public ListOfSamples samples(@RequestParam(name = "limit", defaultValue = "15") int limit) {
        return service.samples(limit);
    }
}
