import org.openimaj.image.ImageUtilities;
import org.openimaj.image.MBFImage;
import org.openimaj.video.capture.VideoCapture;
import org.openimaj.video.capture.VideoCaptureException;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sound.sampled.*;
import javax.swing.*;

class Camera {
    VideoCapture videoCapture;
    public Camera(Dimension dimension) {
        try {
            videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
        } catch (VideoCaptureException exception) {
            exception.printStackTrace();
        }
    }
    public void read(BufferedImage bufferedImage) {
        ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), bufferedImage);
    }
}

class Microphone {
    TargetDataLine targetDataLine;
    public Microphone(AudioFormat audioFormat) {
        try {
            targetDataLine = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
            targetDataLine.open(audioFormat);
            targetDataLine.start();
        } catch (LineUnavailableException exception) {
            exception.printStackTrace();
        }
    }
    public int getBufferSize() {
        return targetDataLine.getBufferSize();
    }
    public int read(byte[] arrayByte) {
        return targetDataLine.read(arrayByte, 0, 1024);
    }
    public void stop() {
        targetDataLine.close();
    }
}

class Display {
    JFrame jFrame = new JFrame();
    JLabel jLabel = new JLabel();
    ImageIcon imageIcon = new ImageIcon();
    public Display(Dimension dimension) {
        jLabel.setIcon(imageIcon);
        jFrame.setSize(dimension);
        jFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        jFrame.getContentPane().add(jLabel);
        jFrame.setVisible(true);
    }
    public void write(BufferedImage bufferedImage) {
        imageIcon.setImage(bufferedImage);
        jLabel.repaint();
    }
}

class Speaker {
    SourceDataLine sourceDataLine;
    public Speaker(AudioFormat audioFormat) {
        try {
            sourceDataLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
            sourceDataLine.open(audioFormat);
            sourceDataLine.start();
        } catch (LineUnavailableException exception) {
            exception.printStackTrace();
        }
    }
    public void write(byte[] arrayByte) {
        sourceDataLine.write(arrayByte, 0, 1024);
    }
    public void stop() {
        sourceDataLine.drain();
        sourceDataLine.close();
    }
}

public class Client {
    static AudioFormat AUDIO_FORMAT = new AudioFormat(8000.0f, 16, 1, true, true);
    static Dimension DIMENSION = new Dimension(320, 240);
    public static void main(String[] arguments) {
        Camera camera = new Camera(DIMENSION);
        Display display = new Display(DIMENSION);
        Microphone microphone = new Microphone(AUDIO_FORMAT);
        Speaker speaker = new Speaker(AUDIO_FORMAT);
        Timer timer = new Timer(1000 / 30, new ActionListener() {
            BufferedImage bufferedImage = new BufferedImage((int) DIMENSION.getWidth(), (int) DIMENSION.getHeight(), BufferedImage.TYPE_INT_ARGB);
            byte[] arrayByte = new byte[microphone.getBufferSize() / 5];
            @Override public void actionPerformed(ActionEvent actionEvent) {
                camera.read(bufferedImage);
                display.write(bufferedImage);
                microphone.read(arrayByte);
                speaker.write(arrayByte);
            }
        });
        timer.start();
    }
}