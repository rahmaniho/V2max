import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** Creates a small, disposable emulator preview using the JDK already in CI. */
public class UiPreview {
    // Eight 3,800-character base64 annotations fit GitHub's per-step limit
    // alongside one checksum and one TUN handover summary.
    private static final int MAX_BYTES = 8 * 3800 / 4 * 3;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("expected input PNG and output JPEG");
        BufferedImage source = ImageIO.read(Path.of(args[0]).toFile());
        if (source == null) throw new IllegalArgumentException("could not decode emulator screenshot");
        int previousWidth = 0;
        for (int maximumWidth : new int[] {480, 400, 320, 280, 240, 200, 160}) {
            double scale = Math.min(1.0, Math.min(
                (double) maximumWidth / source.getWidth(),
                (double) (maximumWidth * 2) / source.getHeight()
            ));
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            if (width == previousWidth) continue;
            previousWidth = width;
            BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = resized.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            for (float quality : new float[] {0.75f, 0.65f, 0.55f, 0.45f, 0.35f, 0.25f, 0.15f}) {
                byte[] bytes = encode(resized, quality);
                if (bytes.length <= MAX_BYTES) {
                    Files.write(Path.of(args[1]), bytes);
                    System.out.printf("UI preview: %dx%d, quality %.2f, %d bytes%n", width, height, quality, bytes.length);
                    return;
                }
            }
        }
        throw new IllegalStateException("emulator preview exceeds annotation capacity");
    }

    private static byte[] encode(BufferedImage image, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IllegalStateException("JDK has no JPEG writer");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }
}
