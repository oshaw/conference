package publishers;

import org.openimaj.image.ImageUtilities;
import org.openimaj.video.capture.VideoCapture;
import org.openimaj.video.capture.VideoCaptureException;
import utilities.BufferCircular;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;

public class Camera extends Publisher<BufferedImage> {
    int framesPerSecond = 30;
    Timer timer;
    VideoCapture videoCapture;

    public Camera(Dimension dimension) {
        try {
            buffer = new BufferCircular<>(new BufferedImage[16]);
            videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
            timer = new Timer(1000 / framesPerSecond, (ActionEvent actionEvent) -> publish());
            timer.start();
        } catch (VideoCaptureException exception) {
            exception.printStackTrace();
        }
    }

    @Override public void publish() {
        ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), buffer.getAvailableSlot());
        buffer.receive();
    }
}