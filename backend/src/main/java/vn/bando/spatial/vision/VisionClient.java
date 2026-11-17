package vn.bando.spatial.vision;

public interface VisionClient {

    String assess(byte[] image, String contentType);
}
