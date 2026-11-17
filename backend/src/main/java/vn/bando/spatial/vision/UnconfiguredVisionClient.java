package vn.bando.spatial.vision;

public final class UnconfiguredVisionClient implements VisionClient {

    @Override
    public String assess(byte[] image, String contentType) {
        throw new IllegalStateException(
                "Chưa có khóa API thị giác. Việc thất bại thay vì bịa kết quả vỉa hè hoặc tầm nhìn.");
    }
}
