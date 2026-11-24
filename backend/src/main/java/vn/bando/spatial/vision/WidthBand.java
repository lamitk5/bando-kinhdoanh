package vn.bando.spatial.vision;

/** Quy ước mét, không phải nhãn do người chấm. */
public final class WidthBand {

    private WidthBand() {
    }

    public static String of(double meters) {
        if (meters < 0.90) {
            return "none";
        }
        if (meters < 1.50) {
            return "narrow";
        }
        if (meters < 3.00) {
            return "enough";
        }
        return "wide";
    }
}
