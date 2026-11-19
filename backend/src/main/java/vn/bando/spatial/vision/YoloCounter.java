package vn.bando.spatial.vision;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/** Đếm vật thể bằng YOLOv8n trên máy này. Ảnh không rời khỏi tiến trình Python. */
@Component
public class YoloCounter implements DisposableBean {

    static final List<String> COUNTS = List.of(
            "person", "car", "motorcycle", "bus", "truck", "traffic_light", "stop_sign");
    static final List<String> UNMEASURED = List.of("utility_pole", "tree", "sign_blocking_visibility");

    private final ObjectMapper mapper;
    private final Path script;
    private final Path python;
    private final String expectedSha;
    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private boolean started;

    public YoloCounter(ObjectMapper mapper) {
        this.mapper = mapper;
        Path root = repositoryRoot();
        this.script = root.resolve("etl/vision/yolo_count.py");
        String override = System.getenv("BANDO_YOLO_PYTHON");
        this.python = override == null || override.isBlank()
                ? root.resolve(".venv-yolo/bin/python")
                : Path.of(override);
        this.expectedSha = readSha(root.resolve("etl/vision/weights/yolov8n.sha256"));
    }

    public synchronized JsonNode count(byte[] image, String contentType) {
        String suffix = switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new IllegalArgumentException("Ảnh YOLO phải là JPEG, PNG hoặc WebP");
        };
        Path file;
        try {
            file = Files.createTempFile("yolo-", suffix);
            Files.write(file, image);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Không ghi được ảnh tạm cho YOLOv8n", exception);
        }
        try {
            return readCount(file);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (java.io.IOException ignored) {
                // Ảnh tạm còn lại không đi vào kết quả.
            }
        }
    }

    @Override
    public synchronized void destroy() {
        stop();
    }

    static void validate(JsonNode node, String expectedSha) {
        if (node == null || !node.isObject() || node.hasNonNull("error") || node.path("ready").asBoolean(false)) {
            throw new IllegalArgumentException("YOLOv8n không trả bản đếm");
        }
        if (!"yolov8n.pt".equals(node.path("weights_file").asText())
                || !expectedSha.equals(node.path("weights_sha256").asText())) {
            throw new IllegalArgumentException("Trọng số YOLOv8n không khớp tệp đã lưu");
        }
        if (!"COCO".equals(node.path("dataset").asText())
                || node.path("confidence_threshold").asDouble() != 0.25
                || !"cpu".equals(node.path("device").asText())
                || node.path("scene_verified").asBoolean(true)
                || !(node.path("inference_ms").asDouble() > 0)
                || node.path("note").asText().isBlank()) {
            throw new IllegalArgumentException("Bản đếm YOLO thiếu mốc đã chốt");
        }
        JsonNode counts = node.path("counts");
        if (!counts.isObject() || counts.size() != COUNTS.size()) {
            throw new IllegalArgumentException("counts không đúng các lớp COCO đã chốt");
        }
        Map<String, Integer> seen = new HashMap<>();
        for (String key : COUNTS) {
            JsonNode value = counts.get(key);
            if (value == null || !value.isIntegralNumber() || value.asInt() < 0) {
                throw new IllegalArgumentException("Số đếm " + key + " không phải số nguyên");
            }
            seen.put(key, 0);
        }
        JsonNode detections = node.path("detections");
        if (!detections.isArray()) {
            throw new IllegalArgumentException("detections phải là mảng");
        }
        for (JsonNode detection : detections) {
            String name = detection.path("class").asText();
            if (!seen.containsKey(name)) {
                throw new IllegalArgumentException("Phát hiện lớp ngoài danh sách COCO đã chốt");
            }
            double confidence = detection.path("confidence").asDouble();
            if (confidence < 0.25 || confidence > 1) {
                throw new IllegalArgumentException("Độ tin cậy nằm ngoài ngưỡng 0,25");
            }
            if (!detection.path("box_px").isArray() || detection.path("box_px").size() != 4) {
                throw new IllegalArgumentException("Hộp phát hiện không đủ bốn cạnh");
            }
            seen.put(name, seen.get(name) + 1);
        }
        for (String key : COUNTS) {
            if (counts.get(key).asInt() != seen.get(key)) {
                throw new IllegalArgumentException("Số đếm " + key + " không khớp danh sách phát hiện");
            }
        }
        JsonNode requested = node.path("requested");
        if (!requested.isObject()) {
            throw new IllegalArgumentException("Thiếu requested");
        }
        for (String key : List.of("person", "car", "motorcycle")) {
            if (!requested.path(key).isIntegralNumber() || requested.path(key).asInt() != counts.path(key).asInt()) {
                throw new IllegalArgumentException("requested." + key + " không khớp số đếm");
            }
        }
        for (String key : UNMEASURED) {
            if (!requested.has(key) || !requested.get(key).isNull()) {
                throw new IllegalArgumentException(key + " không có trong YOLOv8n nên phải là null");
            }
        }
        int found = 0;
        for (JsonNode item : node.path("not_measured")) {
            if (UNMEASURED.contains(item.path("field").asText()) && !item.path("reason").asText().isBlank()) {
                found++;
            }
        }
        if (found != UNMEASURED.size()) {
            throw new IllegalArgumentException("Thiếu lý do cho lớp YOLO không đo");
        }
    }

    private JsonNode readCount(Path file) {
        try {
            ensureStarted();
            stdin.write(file.toAbsolutePath() + "\n");
            stdin.flush();
            String line = stdout.readLine();
            if (line == null) {
                throw new IllegalStateException("YOLOv8n đóng ống kết quả");
            }
            JsonNode node = mapper.readTree(line);
            if (node.hasNonNull("error")) {
                throw new IllegalStateException(node.get("error").asText());
            }
            validate(node, expectedSha);
            return node;
        } catch (IllegalStateException | IllegalArgumentException exception) {
            stop();
            throw exception instanceof IllegalStateException state
                    ? state
                    : new IllegalStateException(exception.getMessage(), exception);
        } catch (java.io.IOException exception) {
            stop();
            throw new IllegalStateException("Không đọc được kết quả YOLOv8n", exception);
        }
    }

    private void ensureStarted() throws java.io.IOException {
        if (started && process != null && process.isAlive()) {
            return;
        }
        if (!Files.isRegularFile(script) || !Files.isExecutable(python)) {
            throw new IllegalStateException("Chưa cài .venv-yolo hoặc thiếu yolo_count.py. Không bịa số đếm.");
        }
        ProcessBuilder builder = new ProcessBuilder(python.toString(), script.toString());
        builder.environment().put("PYTHONUNBUFFERED", "1");
        process = builder.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        Thread stderr = new Thread(() -> {
            try (BufferedReader errors = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                while (errors.readLine() != null) {
                    // Log của thư viện không được trộn vào JSON.
                }
            } catch (java.io.IOException ignored) {
                // Tiến trình đã tắt.
            }
        }, "yolo-stderr");
        stderr.setDaemon(true);
        stderr.start();
        String line = stdout.readLine();
        if (line == null) {
            throw new IllegalStateException(exitMessage());
        }
        JsonNode ready = mapper.readTree(line);
        if (!ready.path("ready").asBoolean(false)
                || ready.path("class_count").asInt() != 80
                || !expectedSha.equals(ready.path("weights_sha256").asText())) {
            throw new IllegalStateException(ready.path("error").asText("YOLOv8n không sẵn sàng"));
        }
        started = true;
    }

    private String exitMessage() throws java.io.IOException {
        try {
            if (process.waitFor(2, TimeUnit.SECONDS)) {
                return "YOLOv8n thoát với mã " + process.exitValue();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return "YOLOv8n không trả dòng sẵn sàng";
    }

    private void stop() {
        started = false;
        if (process != null) {
            process.destroy();
            process = null;
        }
    }

    private static String readSha(Path file) {
        try {
            String text = Files.readString(file).trim();
            String sha = text.split("\\s+")[0];
            if (!sha.matches("[a-f0-9]{64}")) {
                throw new IllegalStateException("Tệp SHA-256 của YOLOv8n không hợp lệ");
            }
            return sha;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Không đọc được SHA-256 của YOLOv8n", exception);
        }
    }

    static Path repositoryRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("etl/vision/yolo_count.py"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("etl/vision/yolo_count.py"))) {
            return parent;
        }
        throw new IllegalStateException("Không thấy etl/vision/yolo_count.py từ " + cwd);
    }
}
