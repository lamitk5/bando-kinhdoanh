package vn.bando.spatial.vision;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/** Câu tư vấn đặt cạnh số đếm trên ảnh, kiểm định cảnh mặt tiền và chỉ số không gian PostGIS. Không kết luận doanh thu. */
public final class CountAdvisory {

    private CountAdvisory() {
    }

    public static String fromCounts(JsonNode requested) {
        return "Đếm trên ảnh: %d xe máy, %d ô tô, %d người đi bộ, %d cột điện, %d cây; biển báo đứng chắn trước mặt tiền: %d. Đầu ra mô hình tại một thời điểm chụp, không phải khảo sát lưu lượng. Không kết luận doanh thu."
                .formatted(
                        integer(requested, "motorcycle"),
                        integer(requested, "car"),
                        integer(requested, "person"),
                        integer(requested, "utility_pole"),
                        integer(requested, "tree"),
                        integer(requested, "sign_blocking_visibility"));
    }

    public static String fromVision(JsonNode yolo, Double sidewalkWidthMeters) {
        return coreVisionClause(yolo, sidewalkWidthMeters) + " Không kết luận doanh thu.";
    }

    private static String coreVisionClause(JsonNode yolo, Double sidewalkWidthMeters) {
        JsonNode requested = yolo.path("requested");
        JsonNode counts = yolo.path("counts");
        int cars = integer(requested, "car");
        int bus = counts.path("bus").isIntegralNumber() ? counts.path("bus").asInt() : 0;
        int truck = counts.path("truck").isIntegralNumber() ? counts.path("truck").asInt() : 0;
        String carLabel = (bus > 0 || truck > 0)
                ? "%d ô tô (con %d, buýt %d, tải %d)".formatted(cars, counts.path("car").asInt(), bus, truck)
                : "%d ô tô".formatted(cars);

        int pedestrians = integer(requested, "person");
        int riders = counts.path("rider").isIntegralNumber() ? counts.path("rider").asInt() : 0;
        String personLabel = riders > 0
                ? "%d người đi bộ (tách riêng %d người ngồi trên xe hai bánh, kể cả người ngồi sau)".formatted(pedestrians, riders)
                : "%d người đi bộ".formatted(pedestrians);

        int totalSigns = counts.path("stop_sign").asInt(0)
                + counts.path("traffic_sign").asInt(0)
                + counts.path("hanging_sign").asInt(0);
        int blockingSigns = integer(requested, "sign_blocking_visibility");
        String signClause = totalSigns > 0
                ? "phát hiện %d biển báo/biển hiệu, trong đó %d biển đứng chắn trực diện trước nền công trình".formatted(totalSigns, blockingSigns)
                : "biển báo đứng chắn trước nền công trình: %d".formatted(blockingSigns);

        String sceneClause;
        boolean verified = yolo.path("scene_verified").asBoolean(false);
        JsonNode scene = yolo.path("scene");
        if (verified && sidewalkWidthMeters != null) {
            sceneClause = "Kiểm định cảnh đạt (công trình dải mặt tiền %s, vỉa hè lộ rõ %s), ước lượng bề rộng vỉa hè %s m trên vùng vỉa hè đã cắt (±0,62 m là MAE kiểm tra của 514 mẫu Seoul, chưa phải sai số đo tại Hà Nội)."
                    .formatted(
                            percent(scene.path("building_ratio").asDouble()),
                            percent(scene.path("sidewalk_ratio").asDouble()),
                            comma2(sidewalkWidthMeters));
        } else if (scene.isObject() && !scene.path("summary").asText().isBlank()) {
            sceneClause = scene.path("summary").asText();
        } else {
            sceneClause = "Chưa đủ mặt tiền tầng trệt và vỉa hè lộ rõ nên không ước lượng bề rộng vỉa hè.";
        }

        return "Đếm trên ảnh: %d xe máy, %s, %s, %d cột điện, %d cây (%s). %s Đây là đầu ra mô hình tại một thời điểm chụp, không phải khảo sát lưu lượng."
                .formatted(
                        integer(requested, "motorcycle"),
                        carLabel,
                        personLabel,
                        integer(requested, "utility_pole"),
                        integer(requested, "tree"),
                        signClause,
                        sceneClause);
    }

    public static String withPlace(
            JsonNode visionResult,
            String businessType,
            JsonNode indicators,
            JsonNode components,
            double spatialScore) {
        JsonNode yolo = visionResult.path("yolo");
        if (!yolo.isObject()) {
            String fallback = visionResult.path("advisory").path("text").asText("");
            return (fallback.isBlank() ? "" : fallback + " ")
                    + placeSummary(businessType, components, spatialScore);
        }
        Double width = visionResult.path("sidewalk_width_m").isNumber()
                ? visionResult.path("sidewalk_width_m").asDouble()
                : null;
        String base = coreVisionClause(yolo, width);

        JsonNode requested = yolo.path("requested");
        JsonNode counts = yolo.path("counts");
        JsonNode scene = yolo.path("scene");
        int moto = integer(requested, "motorcycle");
        int car = integer(requested, "car");
        int person = integer(requested, "person");
        int riders = counts.path("rider").asInt(0);
        int blockingSigns = integer(requested, "sign_blocking_visibility");
        int occPoles = scene.path("occluding_poles").asInt(0);
        int occTrees = scene.path("occluding_trees").asInt(0);
        boolean verified = yolo.path("scene_verified").asBoolean(false);
        double buildingRatio = scene.path("building_ratio").asDouble(0.0);
        double lowerOcc = scene.path("lower_occlusion_ratio").asDouble(0.0);

        double roadDist = indicators.path("arterial_distance_m").asDouble(0.0);
        int competitors = indicators.path("competitor_count").asInt(0);
        int attractors = indicators.path("attractor_count").asInt(0);
        int totalActivity = moto + car + person;

        String facadeObstructionSummary = formatFacadeObstructions(occPoles, occTrees, blockingSigns);
        String crossCheck;
        if (!verified && buildingRatio < 0.12 && (totalActivity + riders == 0)) {
            crossCheck = "Đối chiếu dữ liệu (ảnh không có xác thực tọa độ chụp): ảnh hầu như không có công trình mặt tiền (%s) và không có người/xe, không đại diện mặt bằng đô thị tại tọa độ đang chấm (cách trục chính %s m, %d điểm hút khách và %d đối thủ ngành %s trong 500 m); chỉ giữ điểm không gian PostGIS, không ghép bề rộng vỉa hè."
                    .formatted(
                            percent(buildingRatio),
                            comma1(roadDist),
                            attractors,
                            competitors,
                            sectorLabel(businessType));
        } else if (!verified && lowerOcc > 0.50) {
            crossCheck = "Đối chiếu dữ liệu (ảnh không có xác thực tọa độ chụp): ảnh ghi nhận %d xe máy, %d người đi bộ (kèm %d người đi xe máy), che khuất %s nửa dưới khung hình nên không gán mét vỉa hè; kiểm tra 3D ghi nhận %s; tọa độ đang chấm cách trục chính %s m (%d điểm hút khách, %d đối thủ ngành %s trong 500 m)."
                    .formatted(
                            moto,
                            person,
                            riders,
                            percent(lowerOcc),
                            facadeObstructionSummary,
                            comma1(roadDist),
                            attractors,
                            competitors,
                            sectorLabel(businessType));
        } else if (!verified) {
            crossCheck = "Đối chiếu dữ liệu (ảnh không có xác thực tọa độ chụp): ảnh chưa đủ điều kiện vỉa hè lộ rõ kèm mặt tiền tầng trệt để gán mét bề rộng vỉa hè; trên khung hình ghi nhận %d đối tượng người đi bộ và phương tiện hiện diện cùng %s, đặt cạnh tọa độ cách trục chính %s m (%d điểm hút khách, %d đối thủ ngành %s trong 500 m)."
                    .formatted(
                            totalActivity,
                            facadeObstructionSummary,
                            comma1(roadDist),
                            attractors,
                            competitors,
                            sectorLabel(businessType));
        } else {
            crossCheck = "Đối chiếu dữ liệu cho ngành %s (ảnh không có xác thực tọa độ chụp): trên cảnh mặt tiền đã xác nhận có %d đối tượng người đi bộ và phương tiện hiện diện cùng %s, đặt cạnh tọa độ cách trục chính %s m (%d điểm hút khách, %d đối thủ trong bán kính 500 m)."
                    .formatted(
                            sectorLabel(businessType),
                            totalActivity,
                            facadeObstructionSummary,
                            comma1(roadDist),
                            attractors,
                            competitors);
        }

        return base + " " + crossCheck + " " + placeSummary(businessType, components, spatialScore);
    }

    private static String formatFacadeObstructions(int occPoles, int occTrees, int blockingSigns) {
        int total = occPoles + occTrees + blockingSigns;
        if (total == 0) {
            return "0 vật cản (cột điện, cây, biển báo) đứng chắn trực diện trước nền công trình theo kiểm tra độ sâu 3D";
        }
        return "%d vật cản đứng chắn trước nền công trình theo độ sâu 3D (%d cột điện, %d cây, %d biển báo)"
                .formatted(total, occPoles, occTrees, blockingSigns);
    }

    private static String placeSummary(
            String businessType,
            JsonNode components,
            double spatialScore) {
        return "Chỉ số PostGIS (%s): hút khách %s, bão hòa đối thủ %s, tiếp cận đường %s, điểm không gian %s. Không kết luận doanh thu hay khuyến nghị thuê. Tiền thuê là mốc người gọi."
                .formatted(
                        sectorLabel(businessType),
                        score(components, "attractor_density"),
                        score(components, "competitor_saturation"),
                        score(components, "arterial_access"),
                        comma1(spatialScore));
    }

    private static String sectorLabel(String businessType) {
        return switch (businessType == null ? "" : businessType) {
            case "cafe_drink" -> "cà phê/đồ uống";
            case "pharmacy" -> "nhà thuốc";
            case "convenience" -> "cửa hàng tiện lợi";
            default -> "kinh doanh";
        };
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException("Thiếu số đếm " + field);
        }
        return value.asInt();
    }

    private static String score(JsonNode components, String field) {
        return comma1(components.path(field).path("score").asDouble());
    }

    private static String percent(double ratio) {
        return comma1(ratio * 100.0) + "%";
    }

    private static String comma1(double value) {
        return String.format(Locale.US, "%.1f", value).replace('.', ',');
    }

    private static String comma2(double value) {
        return String.format(Locale.US, "%.2f", value).replace('.', ',');
    }
}
