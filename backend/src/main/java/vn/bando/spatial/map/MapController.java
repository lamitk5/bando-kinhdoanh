package vn.bando.spatial.map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api/map")
public class MapController {

    private final MapService service;

    public MapController(MapService service) {
        this.service = service;
    }

    @GetMapping("/districts")
    public ObjectNode districts() {
        return service.districts();
    }

    @GetMapping("/points")
    public ObjectNode points(
            @RequestParam("west") double west,
            @RequestParam("south") double south,
            @RequestParam("east") double east,
            @RequestParam("north") double north,
            @RequestParam("business_type") String businessType) {
        return service.points(west, south, east, north, businessType);
    }

    @GetMapping("/heatmap")
    public ObjectNode heatmap(
            @RequestParam("west") double west,
            @RequestParam("south") double south,
            @RequestParam("east") double east,
            @RequestParam("north") double north,
            @RequestParam("business_type") String businessType) {
        return service.heatmap(west, south, east, north, businessType);
    }
}
