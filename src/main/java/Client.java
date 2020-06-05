import org.openimaj.image.ImageUtilities;
import org.openimaj.image.MBFImage;
import org.openimaj.video.capture.VideoCapture;
import org.openimaj.video.capture.VideoCaptureException;

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Formatter;
import java.util.List;

public class Client {
    public static void main(String[] arguments) throws VideoCaptureException {
        // Webcam
        Dimension dimension = new Dimension(320, 240);
        VideoCapture videoCapture = new VideoCapture((int) dimension.getHeight(), (int) dimension.getWidth());
        // Create window
        JFrame jFrame = new JFrame();
        jFrame.setSize(dimension);
        jFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        JLabel jLabel = new JLabel();
        jFrame.getContentPane().add(jLabel);
        jFrame.setVisible(true);
        Timer timer = new Timer(1000/30, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                BufferedImage bufferedImage = ImageUtilities.createBufferedImage(videoCapture.getNextFrame());
                jLabel.setIcon(new ImageIcon(bufferedImage));
                jLabel.repaint();
            }
        });
        timer.start();
    }
//    public static void main(String[] arguments) throws LineUnavailableException {
//        // Set up audio format
//        AudioFormat audioFormat = new AudioFormat(8000.0f, 16, 1, true, true);
//        // Microphone, speaker
//        TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
//        SourceDataLine speakers = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
//        microphone.open(audioFormat);
//        microphone.start();
//        speakers.open(audioFormat);
//        speakers.start();
//        // Record sample
//        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
//        int bytesReadTotal = 0;
//        int bytesReadNew;
//        byte[] byteArray = new byte[microphone.getBufferSize() / 5];
//        while (bytesReadTotal < 1000000) {
//            bytesReadNew = microphone.read(byteArray, 0, 1024);
//            bytesReadTotal += bytesReadNew;
//            byteArrayOutputStream.write(byteArray, 0, bytesReadNew);
//            speakers.write(byteArray, 0, bytesReadNew);
//        }
//        microphone.close();
//        speakers.drain();
//        speakers.close();
//    }
//    public static void main(String[] arguments) throws InterruptedException {
//        // Webcam
//        Dimension dimension = new Dimension(320, 240);
//        OpenIMAJGrabber openIMAJGrabber = new OpenIMAJGrabber();
//        Pointer<Device> pointerDevice = openIMAJGrabber.getVideoDevices().get().getDevice(0);
//        openIMAJGrabber.startSession((int) dimension.getWidth(), (int) dimension.getHeight(), 50, pointerDevice);
//        // Set up image format
//        int[] bytesInEachPixel = {8, 8, 8};
//        int[] imageOffset = {0};
//        int[] rgbOffsets = {0, 1, 2};
//        ColorModel colorModel = new ComponentColorModel(
//            ColorSpace.getInstance(ColorSpace.CS_sRGB),
//            bytesInEachPixel,
//            false,
//            false,
//            Transparency.OPAQUE,
//            DataBuffer.TYPE_BYTE
//        );
//        ComponentSampleModel componentSampleModel = new ComponentSampleModel(
//            DataBuffer.TYPE_BYTE,
//            (int) dimension.getWidth(),
//            (int) dimension.getHeight(),
//            3,
//            (int) dimension.getWidth() * 3,
//            rgbOffsets
//        );
//        // Create window
//        JFrame jFrame = new JFrame();
//        jFrame.setSize(dimension);
//        jFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
//        JLabel jLabel = new JLabel();
//        jFrame.getContentPane().add(jLabel);
//        jFrame.setVisible(true);
//        // Stream video
//        Timer timer = new Timer(1000/30, new ActionListener() {
//            @Override
//            public void actionPerformed(ActionEvent actionEvent) {
//                // Take image
//                byte[] byteArray = new byte[(int) (dimension.getWidth() * dimension.getHeight() * 3)];
//                byte[][] byteMatrix = new byte[][] {byteArray};
//                Pointer<Byte> pointerImage = openIMAJGrabber.getImage();
//                ByteBuffer byteBuffer = pointerImage.getByteBuffer((int) (dimension.getWidth() * dimension.getHeight() * 3));
//                byteBuffer.get(byteArray);
//                DataBufferByte dataBufferByte = new DataBufferByte(byteMatrix, byteArray.length, imageOffset);
//                WritableRaster writableRaster = Raster.createWritableRaster(componentSampleModel, dataBufferByte, null);
//                BufferedImage bufferedImage = new BufferedImage(colorModel, writableRaster, false, null);
//                bufferedImage.flush();
//                // Display image
//                jLabel.setIcon(new ImageIcon(bufferedImage));
//                jLabel.repaint();
//                jFrame.repaint();
//            }
//        });
//        timer.start();
//    }
}