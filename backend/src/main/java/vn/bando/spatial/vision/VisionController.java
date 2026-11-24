package vn.bando.spatial.vision;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/vision/jobs")
public class VisionController {

    private final VisionService service;

    public VisionController(VisionService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JsonNode submit(@RequestParam("image") MultipartFile image) {
        return service.submit(image);
    }

    @GetMapping("/{id}")
    public JsonNode get(@PathVariable("id") UUID id) {
        return service.get(id);
    }
}

@RestControllerAdvice
class UploadLimitAdvice {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> tooBig() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "Ảnh vượt quá 4 MB"));
    }
}
