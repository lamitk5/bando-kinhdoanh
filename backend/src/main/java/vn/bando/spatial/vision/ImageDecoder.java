package vn.bando.spatial.vision;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.imageio.ImageIO;

/** Giải mã JPEG, PNG, WebP và chuẩn hóa hướng ảnh theo thẻ EXIF Orientation (0x0112). */
final class ImageDecoder {

    private ImageDecoder() {
    }

    static BufferedImage decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("Không đọc được ảnh JPEG, PNG hoặc WebP.");
        }
        BufferedImage raw;
        try {
            raw = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException exception) {
            throw new IllegalStateException("Không đọc được ảnh JPEG, PNG hoặc WebP.", exception);
        }
        if (raw == null) {
            throw new IllegalStateException("Không đọc được ảnh JPEG, PNG hoặc WebP.");
        }
        return applyOrientation(raw, orientation(bytes));
    }

    static int orientation(byte[] bytes) {
        int jpeg = jpegExifOrientation(bytes);
        if (jpeg != 1) {
            return jpeg;
        }
        int png = pngExifOrientation(bytes);
        if (png != 1) {
            return png;
        }
        return webpExifOrientation(bytes);
    }

    static int jpegExifOrientation(byte[] b) {
        if (b.length < 12 || (b[0] & 0xff) != 0xff || (b[1] & 0xff) != 0xd8) {
            return 1;
        }
        int pos = 2;
        while (pos + 4 <= b.length && (b[pos] & 0xff) == 0xff) {
            while (pos < b.length && (b[pos] & 0xff) == 0xff) {
                pos++;
            }
            if (pos >= b.length) {
                break;
            }
            int marker = b[pos] & 0xff;
            pos++;
            if (marker == 0xda || marker == 0xd9) {
                break;
            }
            if (marker == 0x00 || (marker >= 0xd0 && marker <= 0xd7) || marker == 0x01) {
                continue;
            }
            if (pos + 2 > b.length) {
                break;
            }
            int segLen = ((b[pos] & 0xff) << 8) | (b[pos + 1] & 0xff);
            if (segLen < 2 || pos + segLen > b.length) {
                break;
            }
            if (marker == 0xe1 && segLen >= 14
                    && b[pos + 2] == 'E' && b[pos + 3] == 'x'
                    && b[pos + 4] == 'i' && b[pos + 5] == 'f'
                    && b[pos + 6] == 0 && b[pos + 7] == 0) {
                int tiffStart = pos + 8;
                int tiffLen = segLen - 8;
                ByteOrder order;
                if (b[tiffStart] == 'I' && b[tiffStart + 1] == 'I') {
                    order = ByteOrder.LITTLE_ENDIAN;
                } else if (b[tiffStart] == 'M' && b[tiffStart + 1] == 'M') {
                    order = ByteOrder.BIG_ENDIAN;
                } else {
                    return 1;
                }
                ByteBuffer buf = ByteBuffer.wrap(b, tiffStart, tiffLen).slice().order(order);
                if (buf.remaining() < 8) {
                    return 1;
                }
                int ifdOffset = buf.getInt(4);
                if (ifdOffset < 8 || ifdOffset + 2 > tiffLen) {
                    return 1;
                }
                int entries = buf.getShort(ifdOffset) & 0xffff;
                for (int i = 0; i < entries; i++) {
                    int entryPos = ifdOffset + 2 + i * 12;
                    if (entryPos + 12 > tiffLen) {
                        break;
                    }
                    int tag = buf.getShort(entryPos) & 0xffff;
                    if (tag == 0x0112) {
                        int val = buf.getShort(entryPos + 8) & 0xffff;
                        return (val >= 1 && val <= 8) ? val : 1;
                    }
                }
                return 1;
            }
            pos += segLen;
        }
        return 1;
    }

    static int pngExifOrientation(byte[] b) {
        byte[] sig = new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (b.length < 24) {
            return 1;
        }
        for (int i = 0; i < sig.length; i++) {
            if (b[i] != sig[i]) {
                return 1;
            }
        }
        int pos = 8;
        while (pos + 8 <= b.length) {
            int len = ((b[pos] & 0xff) << 24) | ((b[pos + 1] & 0xff) << 16)
                    | ((b[pos + 2] & 0xff) << 8) | (b[pos + 3] & 0xff);
            if (len < 0 || pos + 12L + len > b.length) {
                return 1;
            }
            String type = new String(b, pos + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if ("eXIf".equals(type)) {
                return orientationInTiff(b, pos + 8, len);
            }
            if ("IEND".equals(type)) {
                return 1;
            }
            pos += 12 + len;
        }
        return 1;
    }

    static int webpExifOrientation(byte[] b) {
        if (b.length < 16 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F'
                || b[8] != 'W' || b[9] != 'E' || b[10] != 'B' || b[11] != 'P') {
            return 1;
        }
        int pos = 12;
        while (pos + 8 <= b.length) {
            String fourcc = new String(b, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = (b[pos + 4] & 0xff) | ((b[pos + 5] & 0xff) << 8)
                    | ((b[pos + 6] & 0xff) << 16) | ((b[pos + 7] & 0xff) << 24);
            int start = pos + 8;
            if (size < 0 || start + (long) size > b.length) {
                return 1;
            }
            if ("EXIF".equals(fourcc)) {
                int tiff = start;
                int tiffLen = size;
                if (size >= 6 && b[start] == 'E' && b[start + 1] == 'x' && b[start + 2] == 'i' && b[start + 3] == 'f') {
                    tiff = start + 6;
                    tiffLen = size - 6;
                }
                return orientationInTiff(b, tiff, tiffLen);
            }
            pos = start + size + (size & 1);
        }
        return 1;
    }

    private static int orientationInTiff(byte[] b, int offset, int length) {
        if (length < 8 || offset < 0 || offset + length > b.length) {
            return 1;
        }
        ByteOrder order;
        if (b[offset] == 'I' && b[offset + 1] == 'I') {
            order = ByteOrder.LITTLE_ENDIAN;
        } else if (b[offset] == 'M' && b[offset + 1] == 'M') {
            order = ByteOrder.BIG_ENDIAN;
        } else {
            return 1;
        }
        ByteBuffer buf = ByteBuffer.wrap(b, offset, length).slice().order(order);
        int ifdOffset = buf.getInt(4);
        if (ifdOffset < 8 || ifdOffset + 2 > length) {
            return 1;
        }
        int entries = buf.getShort(ifdOffset) & 0xffff;
        for (int i = 0; i < entries; i++) {
            int entryPos = ifdOffset + 2 + i * 12;
            if (entryPos + 12 > length) {
                break;
            }
            int tag = buf.getShort(entryPos) & 0xffff;
            if (tag == 0x0112) {
                int val = buf.getShort(entryPos + 8) & 0xffff;
                return (val >= 1 && val <= 8) ? val : 1;
            }
        }
        return 1;
    }

    private static BufferedImage applyOrientation(BufferedImage src, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        int dstW = swap ? h : w;
        int dstH = swap ? w : h;
        BufferedImage dst = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB);
        AffineTransform tx = new AffineTransform();
        switch (orientation) {
            case 2 -> {
                tx.scale(-1, 1);
                tx.translate(-w, 0);
            }
            case 3 -> {
                tx.translate(w, h);
                tx.rotate(Math.PI);
            }
            case 4 -> {
                tx.scale(1, -1);
                tx.translate(0, -h);
            }
            case 5 -> {
                tx.rotate(Math.PI / 2);
                tx.scale(1, -1);
            }
            case 6 -> {
                tx.translate(h, 0);
                tx.rotate(Math.PI / 2);
            }
            case 7 -> {
                tx.scale(-1, 1);
                tx.translate(-h, 0);
                tx.translate(0, w);
                tx.rotate(3 * Math.PI / 2);
            }
            case 8 -> {
                tx.translate(0, w);
                tx.rotate(3 * Math.PI / 2);
            }
            default -> {
                return src;
            }
        }
        Graphics2D g = dst.createGraphics();
        try {
            g.drawImage(src, tx, null);
        } finally {
            g.dispose();
        }
        return dst;
    }
}
