import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Build-time ZXing decoder. No classes from this tool are packaged in the Android app. */
public final class DecodeTemplateCodes {
    private DecodeTemplateCodes() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: DecodeTemplateCodes OUTPUT_JSON IMAGE_OR_DIRECTORY...");
        }
        Path output = Path.of(args[0]);
        List<Path> images = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            Path input = Path.of(args[index]);
            if (Files.isDirectory(input)) {
                try (Stream<Path> stream = Files.list(input)) {
                    stream.filter(Files::isRegularFile).filter(DecodeTemplateCodes::isImage).forEach(images::add);
                }
            } else if (Files.isRegularFile(input) && isImage(input)) {
                images.add(input);
            }
        }
        images.sort(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        StringBuilder json = new StringBuilder("[\n");
        for (int index = 0; index < images.size(); index++) {
            Path image = images.get(index);
            BufferedImage buffered = ImageIO.read(image.toFile());
            List<Result> decoded = buffered == null ? List.of() : decode(buffered);
            if (index > 0) json.append(",\n");
            json.append("  {\"source\":").append(quoted(image.getFileName().toString())).append(",\"codes\":[");
            for (int codeIndex = 0; codeIndex < decoded.size(); codeIndex++) {
                if (codeIndex > 0) json.append(',');
                Result result = decoded.get(codeIndex);
                json.append("{\"format\":").append(quoted(result.getBarcodeFormat().name()));
                json.append(",\"contentBase64\":").append(quoted(Base64.getEncoder().encodeToString(result.getText().getBytes(StandardCharsets.UTF_8))));
                json.append(",\"points\":[");
                ResultPoint[] points = result.getResultPoints();
                if (points != null) {
                    for (int pointIndex = 0; pointIndex < points.length; pointIndex++) {
                        if (pointIndex > 0) json.append(',');
                        json.append('[').append(number(points[pointIndex].getX())).append(',').append(number(points[pointIndex].getY())).append(']');
                    }
                }
                json.append("]}");
            }
            json.append("]}");
            System.out.printf(Locale.ROOT, "[%03d/%03d] %s codes=%d%n", index + 1, images.size(), image.getFileName(), decoded.size());
        }
        json.append("\n]\n");
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, json.toString(), StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "CODE_SCAN_COMPLETE files=%d output=%s%n", images.size(), output);
    }

    private static List<Result> decode(BufferedImage image) {
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageSource(image)));
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        MultiFormatReader delegate = new MultiFormatReader();
        delegate.setHints(hints);
        List<Result> values = new ArrayList<>();
        try {
            Result[] results = new GenericMultipleBarcodeReader(delegate).decodeMultiple(bitmap, hints);
            if (results != null) {
                for (Result result : results) addUnique(values, result);
            }
        } catch (NotFoundException ignored) {
            try {
                addUnique(values, delegate.decode(bitmap, hints));
            } catch (NotFoundException ignoredAgain) {
                // A non-decodable decorative pattern is deliberately not reported as real data.
            }
        }
        return values;
    }

    private static void addUnique(List<Result> values, Result candidate) {
        for (Result value : values) {
            if (value.getBarcodeFormat() == candidate.getBarcodeFormat() && value.getText().equals(candidate.getText())) return;
        }
        values.add(candidate);
    }

    private static boolean isImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    private static String number(float value) {
        if (!Float.isFinite(value)) return "0";
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String quoted(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    else out.append(character);
                }
            }
        }
        return out.append('"').toString();
    }

    private static final class BufferedImageSource extends LuminanceSource {
        private final byte[] luminance;
        private final int dataWidth;
        private final int dataHeight;
        private final int left;
        private final int top;

        BufferedImageSource(BufferedImage image) {
            this(toLuminance(image), image.getWidth(), image.getHeight(), 0, 0, image.getWidth(), image.getHeight());
        }

        private BufferedImageSource(byte[] luminance, int dataWidth, int dataHeight, int left, int top, int width, int height) {
            super(width, height);
            this.luminance = luminance;
            this.dataWidth = dataWidth;
            this.dataHeight = dataHeight;
            this.left = left;
            this.top = top;
        }

        @Override public byte[] getRow(int y, byte[] row) {
            if (y < 0 || y >= getHeight()) throw new IllegalArgumentException("row outside image");
            if (row == null || row.length < getWidth()) row = new byte[getWidth()];
            System.arraycopy(luminance, (y + top) * dataWidth + left, row, 0, getWidth());
            return row;
        }

        @Override public byte[] getMatrix() {
            if (getWidth() == dataWidth && getHeight() == dataHeight) return luminance;
            byte[] matrix = new byte[getWidth() * getHeight()];
            for (int y = 0; y < getHeight(); y++) {
                System.arraycopy(luminance, (y + top) * dataWidth + left, matrix, y * getWidth(), getWidth());
            }
            return matrix;
        }

        @Override public boolean isCropSupported() { return true; }

        @Override public LuminanceSource crop(int cropLeft, int cropTop, int width, int height) {
            return new BufferedImageSource(luminance, dataWidth, dataHeight, left + cropLeft, top + cropTop, width, height);
        }

        private static byte[] toLuminance(BufferedImage image) {
            int width = image.getWidth();
            int height = image.getHeight();
            byte[] result = new byte[width * height];
            int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
            for (int index = 0; index < pixels.length; index++) {
                int pixel = pixels[index];
                int alpha = (pixel >>> 24) & 0xff;
                int red = (pixel >>> 16) & 0xff;
                int green = (pixel >>> 8) & 0xff;
                int blue = pixel & 0xff;
                int gray = (red * 306 + green * 601 + blue * 117) >> 10;
                result[index] = (byte) ((gray * alpha + 255 * (255 - alpha)) / 255);
            }
            return result;
        }
    }
}
