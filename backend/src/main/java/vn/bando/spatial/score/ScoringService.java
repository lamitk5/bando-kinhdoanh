package vn.bando.spatial.score;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import vn.bando.spatial.BufferRequest;
import vn.bando.spatial.vision.VisionJobRepository;

@Service
public class ScoringService {

    private static final Set<String> FIELDS = Set.of(
            "lat", "lon", "business_type", "rent_vnd_per_month", "vision_job_id", "blend_weight");

    private final ScoringRepository scoring;
    private final VisionJobRepository jobs;
    private final ObjectMapper mapper;

    public ScoringService(ScoringRepository scoring, VisionJobRepository jobs, ObjectMapper mapper) {
        this.scoring = scoring;
        this.jobs = jobs;
        this.mapper = mapper;
    }

    public ObjectNode reference() {
        ObjectNode body = read(scoring.reference());
        body.put("matrix_label", WorkingJudgments.LABEL);
        body.put("random_index_n3", Ahp.RANDOM_INDEX_N3);
        body.put("scored_ring_m", 500);
        body.put("unscored_rings_m", "200 và 1000 chỉ nằm ở API vùng đệm, không vào điểm AHP");
        ArrayNode matrices = body.putArray("matrices");
        for (String business : new String[] {"cafe_drink", "pharmacy", "convenience"}) {
            matrices.add(matrixNode(business));
        }
        return body;
    }

    public ObjectNode refresh() {
        return read(scoring.refresh());
    }

    public ObjectNode evaluate(JsonNode request) {
        rejectUnknown(request);
        require(request, "lat", "lon", "business_type", "rent_vnd_per_month");
        BufferRequest.Normalized point;
        try {
            point = BufferRequest.normalize(number(request, "lat"), number(request, "lon"), text(request, "business_type"));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
        long rent = rent(request);
        JsonNode indicators = read(scoring.indicators(point.lon(), point.lat(), point.businessType()));
        JsonNode scale = read(scoring.reference());
        Ahp.Result judgment = Ahp.solve3(WorkingJudgments.spatialMatrix(point.businessType()));
        Ahp.requireConsistent(judgment);

        double attractorScale = requiredPositive(scale, "attractor_density_p95");
        double competitorScale = requiredPositive(scale, competitorField(point.businessType()));
        double median = requiredPositive(scale, "arterial_distance_median_m");
        double attractor = ScoreMath.higherIsBetter(indicators.path("attractor_density_per_km2").asDouble(), attractorScale);
        double saturation = ScoreMath.saturation(indicators.path("competitor_density_per_km2").asDouble(), competitorScale);
        double access = ScoreMath.access(indicators.path("arterial_distance_m").asDouble(), median);
        double spatial = score(point.businessType(), indicators, scale, false);

        ObjectNode response = mapper.createObjectNode();
        response.put("lat", point.lat());
        response.put("lon", point.lon());
        response.put("business_type", point.businessType());
        response.put("rent_vnd_per_month", rent);
        response.put("scored_ring_m", 500);
        response.put("partial_ring", !indicators.path("counts_complete").asBoolean());
        response.set("indicators", indicators);
        response.put("reference_computed_at", scale.path("computed_at").asText());
        response.set("components", components(indicators, attractorScale, competitorScale, median, attractor, saturation, access));
        response.set("weights", weights(judgment.weights()));
        response.put("consistency_ratio", round4(judgment.consistencyRatio()));
        response.put("matrix_label", WorkingJudgments.LABEL);
        response.put("spatial_score", spatial);
        response.put("rent_index_points_per_million_vnd", round4(ScoreMath.pointsPerMillionVnd(spatial, rent)));
        response.put("rent_index_meaning", "Điểm không gian trên mỗi triệu đồng tiền thuê một tháng. Không phải lợi nhuận vì chưa có doanh thu.");
        applyVision(response, request, point.businessType(), spatial);
        return response;
    }

    public JsonNode referenceNode() {
        return read(scoring.reference());
    }

    public double score(String business, JsonNode indicators, JsonNode scale, boolean removeSelf) {
        Ahp.Result judgment = Ahp.solve3(WorkingJudgments.spatialMatrix(business));
        Ahp.requireConsistent(judgment);
        double attractorScale = requiredPositive(scale, "attractor_density_p95");
        double competitorScale = requiredPositive(scale, competitorField(business));
        double roadMedian = requiredPositive(scale, "arterial_distance_median_m");
        double density = indicators.path("competitor_density_per_km2").asDouble();
        if (removeSelf) {
            int competitors = indicators.path("competitor_count").asInt();
            double area = indicators.path("searched_area_m2").asDouble();
            density = Math.max(0, competitors - 1) / (area / 1_000_000.0);
        }
        double attractor = ScoreMath.higherIsBetter(indicators.path("attractor_density_per_km2").asDouble(), attractorScale);
        double saturation = ScoreMath.saturation(density, competitorScale);
        double access = ScoreMath.access(indicators.path("arterial_distance_m").asDouble(), roadMedian);
        return round1(ScoreMath.weighted(judgment.weights(), attractor, saturation, access));
    }

    public ObjectNode compare(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cần một đối tượng JSON");
        }
        request.fieldNames().forEachRemaining(field -> {
            if (!field.equals("business_type") && !field.equals("rent_vnd_per_month") && !field.equals("sites")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trường không được gửi: " + field);
            }
        });
        require(request, "business_type", "rent_vnd_per_month", "sites");
        JsonNode sites = request.get("sites");
        if (!sites.isArray() || sites.size() < 2 || sites.size() > 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cần từ 2 đến 3 mặt bằng");
        }
        java.util.Set<String> labels = new java.util.HashSet<>();
        ObjectNode response = mapper.createObjectNode();
        response.put("business_type", text(request, "business_type"));
        response.put("rent_vnd_per_month", rent(request));
        response.put("radar_note", "Ba cánh là điểm thành phần 0–100 của cùng một ngành và cùng một mức thuê. Không phải doanh thu.");
        var compared = response.putArray("sites");
        for (JsonNode site : sites) {
            if (!site.isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mỗi mặt bằng phải là một đối tượng");
            }
            site.fieldNames().forEachRemaining(field -> {
                if (!field.equals("label") && !field.equals("lat") && !field.equals("lon")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trường không được gửi: " + field);
                }
            });
            if (!site.hasNonNull("label") || !site.path("label").isTextual()
                    || !site.path("lat").isNumber() || !site.path("lon").isNumber()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mỗi mặt bằng cần label, lat và lon");
            }
            String label = site.get("label").asText().trim();
            if (label.isEmpty() || label.length() > 40 || !labels.add(label)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nhãn mặt bằng phải khác nhau và không quá 40 ký tự");
            }
            ObjectNode one = mapper.createObjectNode();
            one.put("lat", site.get("lat").asDouble());
            one.put("lon", site.get("lon").asDouble());
            one.put("business_type", text(request, "business_type"));
            one.put("rent_vnd_per_month", rent(request));
            ObjectNode scored = evaluate(one);
            ObjectNode row = compared.addObject();
            row.put("label", label);
            row.put("lat", scored.path("lat").asDouble());
            row.put("lon", scored.path("lon").asDouble());
            row.put("spatial_score", scored.path("spatial_score").asDouble());
            row.put("rent_index_points_per_million_vnd", scored.path("rent_index_points_per_million_vnd").asDouble());
            row.set("components", scored.path("components"));
            row.put("partial_ring", scored.path("partial_ring").asBoolean());
        }
        return response;
    }

    private void applyVision(ObjectNode response, JsonNode request, String business, double spatial) {
        response.put("vision_applied", false);
        response.putNull("vision_job_id");
        response.putNull("image_score");
        response.putNull("blend_weight");
        response.putNull("composite_score");
        boolean hasBlend = request.hasNonNull("blend_weight");
        if (!request.hasNonNull("vision_job_id")) {
            if (hasBlend) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "blend_weight chỉ dùng khi có vision_job_id đã được thẩm định");
            }
            return;
        }
        UUID id = jobId(request);
        JsonNode job = read(jobs.findJson(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Không thấy việc ảnh " + id)));
        String status = job.path("status").asText();
        if (!"completed".equals(status)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ảnh chưa được chấm. Trạng thái " + status + ". Ảnh kém không bị tính điểm 0.");
        }
        JsonNode result = job.path("result");
        boolean full = result.path("assessed").asBoolean()
                && !result.path("sidewalk_score").isNull()
                && !result.path("visibility_score").isNull();
        if (!full) {
            boolean widthOnly = !result.path("sidewalk_score").isNull() && result.path("visibility_score").isNull();
            if (widthOnly && !hasBlend) {
                response.put("vision_job_id", id.toString());
                response.put("visibility_measured", false);
                if (result.path("sidewalk_width_m").isNumber()) {
                    response.put("sidewalk_width_m", result.path("sidewalk_width_m").asDouble());
                } else {
                    response.putNull("sidewalk_width_m");
                }
                response.put("sidewalk_capacity", result.path("sidewalk_capacity").asText(null));
                response.put("sidewalk_score", result.path("sidewalk_score").asInt());
                response.put("vision_note", "Chỉ gắn ước lượng bề rộng. Không có tầm nhìn nên không tạo điểm ảnh và không trộn vào điểm tổng.");
                return;
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    widthOnly
                            ? "Chưa có tầm nhìn nên không trộn vào điểm tổng."
                            : "Ảnh chưa có điểm thứ tự để đưa vào tổng hợp");
        }
        double[] imageWeights = Ahp.pairWeights(WorkingJudgments.sidewalkVersusVisibility(business));
        double image = ScoreMath.imageScore(
                imageWeights,
                result.path("sidewalk_score").asDouble(),
                result.path("visibility_score").asDouble());
        response.put("vision_applied", true);
        response.put("vision_job_id", id.toString());
        response.put("image_score", round1(image));
        response.set("image_weights", mapper.createObjectNode()
                .put("sidewalk", round4(imageWeights[0]))
                .put("visibility", round4(imageWeights[1])));
        response.put("image_weight_note", "Trọng số ảnh là so sánh cặp làm việc, không đo bằng mét.");
        if (!hasBlend) {
            return;
        }
        double blend = number(request, "blend_weight");
        if (blend < 0 || blend > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "blend_weight phải nằm trong khoảng 0 đến 1");
        }
        response.put("blend_weight", blend);
        response.put("composite_score", round1(blend * spatial + (1.0 - blend) * image));
        response.put("composite_meaning", "Người gọi chọn tỷ lệ. Hệ thống không tự đặt 70/30.");
    }

    private ObjectNode matrixNode(String business) {
        Ahp.Result result = Ahp.solve3(WorkingJudgments.spatialMatrix(business));
        Ahp.requireConsistent(result);
        ObjectNode node = mapper.createObjectNode();
        node.put("business_type", business);
        node.put("rationale", WorkingJudgments.spatialRationale(business));
        node.set("criteria", mapper.valueToTree(WorkingJudgments.SPATIAL_CRITERIA));
        node.set("weights", weights(result.weights()));
        node.put("consistency_ratio", round4(result.consistencyRatio()));
        return node;
    }

    private ObjectNode components(
            JsonNode indicators,
            double attractorScale,
            double competitorScale,
            double median,
            double attractor,
            double saturation,
            double access) {
        ObjectNode node = mapper.createObjectNode();
        node.set("attractor_density", component(indicators.path("attractor_density_per_km2").asDouble(), attractorScale, attractor, "cao hơn thì tốt hơn"));
        node.set("competitor_saturation", component(indicators.path("competitor_density_per_km2").asDouble(), competitorScale, saturation, "cao hơn thì xấu hơn"));
        node.set("arterial_access", component(indicators.path("arterial_distance_m").asDouble(), median, access, "xa hơn thì thấp hơn, trung vị được 50"));
        return node;
    }

    private ObjectNode component(double raw, double scale, double score, String direction) {
        ObjectNode node = mapper.createObjectNode();
        node.put("raw", raw);
        node.put("scale", scale);
        node.put("score", round1(score));
        node.put("direction", direction);
        return node;
    }

    private ArrayNode weights(double[] weights) {
        ArrayNode array = mapper.createArrayNode();
        for (double weight : weights) {
            array.add(round4(weight));
        }
        return array;
    }

    private static String competitorField(String business) {
        return switch (business) {
            case "cafe_drink" -> "cafe_competitor_density_p95";
            case "pharmacy" -> "pharmacy_competitor_density_p95";
            case "convenience" -> "convenience_competitor_density_p95";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "business_type không hợp lệ");
        };
    }

    private static double requiredPositive(JsonNode node, String field) {
        double value = node.path(field).asDouble();
        if (!(value > 0)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Thang " + field + " không dùng được");
        }
        return value;
    }

    private static long rent(JsonNode request) {
        JsonNode value = request.get("rent_vnd_per_month");
        if (value == null || !value.isIntegralNumber()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rent_vnd_per_month phải là số nguyên dương");
        }
        long rent = value.longValue();
        if (rent <= 0 || rent >= 1_000_000_000_000L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rent_vnd_per_month phải lớn hơn 0 và nhỏ hơn 1000000000000");
        }
        return rent;
    }

    private static UUID jobId(JsonNode request) {
        try {
            return UUID.fromString(text(request, "vision_job_id"));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "vision_job_id phải là UUID", exception);
        }
    }

    private ObjectNode read(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            if (node instanceof ObjectNode object) {
                return object;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "JSON chấm điểm không đọc được", exception);
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "JSON chấm điểm không đọc được");
    }

    private static void rejectUnknown(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cần một đối tượng JSON");
        }
        body.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trường không được gửi: " + field);
            }
        });
    }

    private static void require(JsonNode body, String... fields) {
        for (String field : fields) {
            if (!body.hasNonNull(field)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu trường " + field);
            }
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trường " + field + " phải là chuỗi");
        }
        return value.asText();
    }

    private static double number(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isNumber()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trường " + field + " phải là số");
        }
        return value.asDouble();
    }

    static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
