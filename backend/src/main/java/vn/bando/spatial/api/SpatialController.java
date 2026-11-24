package vn.bando.spatial.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    @GetMapping("/buffers")
    public ObjectNode buffers(
            @RequestParam("lat") double lat,
            @RequestParam("lon") double lon,
            @RequestParam("business_type") String businessType) {
        return service.buffer(lat, lon, businessType);
    }

    @PostMapping("/contributions")
    public JsonNode submitContribution(@RequestBody JsonNode body) {
        return service.submitContribution(body);
    }

    @GetMapping("/contributions")
    public JsonNode contributions(@RequestParam(name = "status", required = false) String status) {
        return service.listContributions(status);
    }

    @PostMapping("/contributions/{id}/approve")
    public JsonNode approveContribution(
            @PathVariable("id") long id,
            @RequestHeader(name = "X-Reviewer-Token", required = false) String reviewerToken,
            @RequestBody(required = false) JsonNode body) {
        return service.decideContribution(id, "approved", body, reviewerToken);
    }

    @PostMapping("/contributions/{id}/reject")
    public JsonNode rejectContribution(
            @PathVariable("id") long id,
            @RequestHeader(name = "X-Reviewer-Token", required = false) String reviewerToken,
            @RequestBody JsonNode body) {
        return service.decideContribution(id, "rejected", body, reviewerToken);
    }

    @GetMapping("/samples")
    public ListOfSamples samples(@RequestParam(name = "limit", defaultValue = "15") int limit) {
        return service.samples(limit);
    }
}
