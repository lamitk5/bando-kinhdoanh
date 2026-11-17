package vn.bando.spatial.score;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.bando.spatial.report.ReportPdf;

@RestController
@RequestMapping("/api/scoring")
public class ScoringController {

    private final ScoringService service;

    public ScoringController(ScoringService service) {
        this.service = service;
    }

    @GetMapping("/reference")
    public ObjectNode reference() {
        return service.reference();
    }

    @PostMapping("/reference/refresh")
    public ObjectNode refresh() {
        return service.refresh();
    }

    @PostMapping("/evaluate")
    public ObjectNode evaluate(@RequestBody JsonNode body) {
        return service.evaluate(body);
    }

    @PostMapping("/compare")
    public ObjectNode compare(@RequestBody JsonNode body) {
        return service.compare(body);
    }

    @PostMapping(value = "/compare.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> comparePdf(@RequestBody JsonNode body) {
        byte[] pdf = ReportPdf.write(service.compare(body));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"so-sanh.pdf\"")
                .body(pdf);
    }
}
