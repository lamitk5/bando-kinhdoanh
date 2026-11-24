package vn.bando.spatial.score;

public final class WorkingJudgments {

    public static final String LABEL = "ma trận làm việc của đề tài, chưa phải phiếu chuyên gia";

    public static final String[] SPATIAL_CRITERIA = {
            "attractor_density",
            "competitor_saturation",
            "arterial_access"
    };

    private WorkingJudgments() {
    }

    public static double[][] spatialMatrix(String businessType) {
        return switch (businessType) {
            case "cafe_drink" -> new double[][] {
                    {1, 1.0 / 3.0, 0.5},
                    {3, 1, 2},
                    {2, 0.5, 1}
            };
            case "pharmacy" -> new double[][] {
                    {1, 3, 2},
                    {1.0 / 3.0, 1, 0.5},
                    {0.5, 2, 1}
            };
            case "convenience" -> new double[][] {
                    {1, 4, 2},
                    {0.25, 1, 1.0 / 3.0},
                    {0.5, 3, 1}
            };
            default -> throw new IllegalArgumentException("business_type phải là cafe_drink, pharmacy hoặc convenience");
        };
    }

    public static String spatialRationale(String businessType) {
        return switch (businessType) {
            case "cafe_drink" -> "Đối thủ quan trọng gấp 3 điểm hút và gấp 2 tiếp cận đường. Tiếp cận đường quan trọng gấp 2 điểm hút.";
            case "pharmacy" -> "Điểm hút quan trọng gấp 3 đối thủ và gấp 2 tiếp cận đường. Tiếp cận đường quan trọng gấp 2 đối thủ.";
            case "convenience" -> "Điểm hút quan trọng gấp 4 đối thủ và gấp 2 tiếp cận đường. Tiếp cận đường quan trọng gấp 3 đối thủ.";
            default -> throw new IllegalArgumentException("business_type phải là cafe_drink, pharmacy hoặc convenience");
        };
    }

    public static double sidewalkVersusVisibility(String businessType) {
        return switch (businessType) {
            case "cafe_drink" -> 0.5;
            case "pharmacy" -> 2.0;
            case "convenience" -> 1.0;
            default -> throw new IllegalArgumentException("business_type phải là cafe_drink, pharmacy hoặc convenience");
        };
    }
}
