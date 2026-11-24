package vn.bando.spatial.vision;

public final class ImageMagic {

    private ImageMagic() {
    }

    public static String contentType(byte[] bytes) {
        if (bytes == null || bytes.length < 12 || bytes.length > 4_000_000) {
            throw new IllegalArgumentException("Ảnh phải là JPEG, PNG hoặc WebP và không quá 4 MB");
        }
        if ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a) {
            return "image/png";
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        throw new IllegalArgumentException("Ảnh phải là JPEG, PNG hoặc WebP");
    }
}
