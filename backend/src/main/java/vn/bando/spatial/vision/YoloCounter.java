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
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/** Đếm vật thể và kiểm định cảnh mặt tiền trên máy này. Ảnh không rời khỏi tiến trình Python. */
@Component
public class YoloCounter implements DisposableBean {

    static final List<String> COUNTS = List.of(
            "person", "rider", "car", "motorcycle", "bus", "truck", "traffic_light", "stop_sign",
            "tree", "hanging_sign", "utility_pole", "traffic_sign");
    static final List<String> REQUESTED = List.of(
            "person", "car", "motorcycle", "utility_pole", "tree", "sign_blocking_visibility");
    private static final Set<String> DETECTOR_SOURCES = Set.of("yolov8m", "yolov8s-worldv2");
    private static final Set<String> SIGN_CLASSES = Set.of("stop_sign", "traffic_sign", "hanging_sign");
    private static final long READ_TIMEOUT_SECONDS = 30L;

    private final ObjectMapper mapper;
    private final Path script;
    private final Path python;
    private final String cocoSha;
    private final String worldSha;
    private final String segmenterSha;
    private final String depthSha;
    private final ExecutorService lineReader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "yolo-stdout-reader");
        thread.setDaemon(true);
        return thread;
    });
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
        this.cocoSha = readSha(root.resolve("etl/vision/weights/yolov8m.sha256"));
        this.worldSha = readSha(root.resolve("etl/vision/weights/yolov8s-worldv2.sha256"));
        this.segmenterSha = readSha(root.resolve("etl/vision/weights/segformer-cityscapes.sha256"));
        this.depthSha = readSha(root.resolve("etl/vision/weights/depth-anything-v2-small.sha256"));
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
            throw new IllegalStateException("Không ghi được ảnh tạm để đếm vật thể", exception);
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
        lineReader.shutdownNow();
    }

    static void validate(JsonNode node, String cocoSha, String worldSha, String segmenterSha, String depthSha) {
        if (node == null || !node.isObject() || node.hasNonNull("error") || node.path("ready").asBoolean(false)) {
            throw new IllegalArgumentException("Bộ đếm không trả bản đếm");
        }
        if (!"yolov8m.pt".equals(node.path("weights_file").asText())
                || !cocoSha.equals(node.path("coco_sha256").asText())
                || !worldSha.equals(node.path("weights_sha256").asText())
                || !segmenterSha.equals(node.path("segmenter_sha256").asText())
                || !depthSha.equals(node.path("depth_sha256").asText())) {
            throw new IllegalArgumentException("Trọng số đếm không khớp tệp đã lưu");
        }
        if (node.path("confidence_threshold").asDouble() != 0.25
                || node.path("min_area_fraction").asDouble() != 0.0005
                || !"cpu".equals(node.path("device").asText())
                || !node.path("scene_verified").isBoolean()
                || !(node.path("inference_ms").asDouble() > 0)
                || node.path("note").asText().isBlank()
                || node.path("occlusion_rule").asText().isBlank()) {
            throw new IllegalArgumentException("Bản đếm thiếu mốc đã chốt");
        }
        JsonNode scene = node.path("scene");
        if (!scene.isObject()
                || !scene.path("verified").isBoolean()
                || scene.path("verified").asBoolean() != node.path("scene_verified").asBoolean()
                || !scene.path("building_ratio").isNumber()
                || !scene.path("sidewalk_ratio").isNumber()
                || !scene.path("lower_pavement_ratio").isNumber()
                || !scene.path("lower_occlusion_ratio").isNumber()
                || scene.path("summary").asText().isBlank()) {
            throw new IllegalArgumentException("Thiếu thông số kiểm định cảnh mặt tiền");
        }
        JsonNode counts = node.path("counts");
        if (!counts.isObject() || counts.size() != COUNTS.size()) {
            throw new IllegalArgumentException("counts không đúng các lớp đã chốt");
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
        int blocking = 0;
        for (JsonNode detection : detections) {
            String name = detection.path("class").asText();
            if (!seen.containsKey(name)) {
                throw new IllegalArgumentException("Phát hiện lớp ngoài danh sách đã chốt");
            }
            String source = detection.path("source").asText();
            JsonNode confidence = detection.get("confidence");
            if (DETECTOR_SOURCES.contains(source)) {
                if (confidence == null || !confidence.isNumber() || confidence.asDouble() < 0.25 || confidence.asDouble() > 1) {
                    throw new IllegalArgumentException("Độ tin cậy YOLO nằm ngoài ngưỡng 0,25");
                }
            } else if ("segformer-cityscapes".equals(source)) {
                if (confidence == null || !confidence.isNumber() || confidence.asDouble() < 0.45 || confidence.asDouble() > 1) {
                    throw new IllegalArgumentException("Xác suất vùng SegFormer nằm ngoài ngưỡng 0,45");
                }
            } else {
                throw new IllegalArgumentException("Nguồn phát hiện không thuộc mô hình đã chốt");
            }
            if (!detection.path("box_px").isArray() || detection.path("box_px").size() != 4) {
                throw new IllegalArgumentException("Hộp phát hiện không đủ bốn cạnh");
            }
            if (detection.path("blocks_facade").asBoolean(false)) {
                if (!SIGN_CLASSES.contains(name)) {
                    throw new IllegalArgumentException("Chỉ biển báo mới được tính là che mặt tiền");
                }
                blocking++;
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
        for (String key : List.of("person", "motorcycle", "utility_pole", "tree")) {
            if (!requested.path(key).isIntegralNumber() || requested.path(key).asInt() != counts.path(key).asInt()) {
                throw new IllegalArgumentException("requested." + key + " không khớp số đếm");
            }
        }
        int expectedCars = counts.path("car").asInt() + counts.path("bus").asInt() + counts.path("truck").asInt();
        if (!requested.path("car").isIntegralNumber() || requested.path("car").asInt() != expectedCars) {
            throw new IllegalArgumentException("requested.car phải bằng tổng car, bus và truck");
        }
        if (!requested.path("sign_blocking_visibility").isIntegralNumber()
                || requested.path("sign_blocking_visibility").asInt() != blocking) {
            throw new IllegalArgumentException("Số biển báo che mặt tiền không khớp các hộp");
        }
    }

    private JsonNode readCount(Path file) {
        try {
            ensureStarted();
            stdin.write(file.toAbsolutePath() + "\n");
            stdin.flush();
            String line = readLineWithTimeout();
            if (line == null) {
                throw new IllegalStateException("Bộ đếm đóng ống kết quả");
            }
            JsonNode node = mapper.readTree(line);
            if (node.hasNonNull("error")) {
                throw new IllegalStateException(node.get("error").asText());
            }
            validate(node, cocoSha, worldSha, segmenterSha, depthSha);
            return node;
        } catch (IllegalStateException | IllegalArgumentException exception) {
            stop();
            throw exception instanceof IllegalStateException state
                    ? state
                    : new IllegalStateException(exception.getMessage(), exception);
        } catch (java.io.IOException exception) {
            stop();
            throw new IllegalStateException("Không đọc được kết quả đếm", exception);
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
        String line = readLineWithTimeout();
        if (line == null) {
            throw new IllegalStateException(exitMessage());
        }
        JsonNode ready = mapper.readTree(line);
        if (!ready.path("ready").asBoolean(false)
                || ready.path("class_count").asInt() != 9
                || !cocoSha.equals(ready.path("coco_sha256").asText())
                || !worldSha.equals(ready.path("weights_sha256").asText())
                || !segmenterSha.equals(ready.path("segmenter_sha256").asText())
                || !depthSha.equals(ready.path("depth_sha256").asText())) {
            throw new IllegalStateException(ready.path("error").asText("Bộ đếm không sẵn sàng"));
        }
        started = true;
    }

    private String readLineWithTimeout() throws java.io.IOException {
        BufferedReader reader = this.stdout;
        Future<String> future = lineReader.submit(reader::readLine);
        try {
            return future.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            stop();
            throw new IllegalStateException("Quá thời gian chờ (" + READ_TIMEOUT_SECONDS + "s) từ tiến trình đếm vật thể", exception);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gián đoạn khi chờ kết quả đếm vật thể", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof java.io.IOException io) {
                throw io;
            }
            throw new IllegalStateException("Lỗi khi đọc kết quả đếm vật thể", exception.getCause());
        }
    }

    private String exitMessage() {
        try {
            if (process != null && process.waitFor(2, TimeUnit.SECONDS)) {
                return "Bộ đếm thoát với mã " + process.exitValue();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return "Bộ đếm không trả dòng sẵn sàng";
    }

    private void stop() {
        started = false;
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
    }

    private static String readSha(Path file) {
        try {
            String sha = Files.readString(file).trim().split("\\s+")[0];
            if (!sha.matches("[a-f0-9]{64}")) {
                throw new IllegalStateException("Tệp SHA-256 của bộ đếm không hợp lệ");
            }
            return sha;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Không đọc được SHA-256 của bộ đếm", exception);
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
