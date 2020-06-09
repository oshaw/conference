package publishers;

import org.openimaj.image.ImageUtilities;
import org.openimaj.video.capture.VideoCapture;
import org.openimaj.video.capture.VideoCaptureException;
import utilities.BufferCircular;
import utilities.PacketVideo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.net.SocketAddress;

public class Camera extends Publisher<PacketVideo> {
    int framesPerSecond = 30;
    PacketVideo packetVideo;
    Timer timer;
    VideoCapture videoCapture;

    public Camera(SocketAddress socketAddress, Dimension dimension) {
        try {
            PacketVideo[] packetVideos = new PacketVideo[16];
            for (PacketVideo packetVideo : packetVideos) packetVideo.socketAddress = socketAddress;
            buffer = new BufferCircular<>(packetVideos);

            videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
            timer = new Timer(1000 / framesPerSecond, (ActionEvent actionEvent) -> publish());
            timer.start();
        } catch (VideoCaptureException exception) {
            exception.printStackTrace();
        }
    }

    @Override public void publish() {
        packetVideo = buffer.getAvailableSlot();
        ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), packetVideo.bufferedImage);
        buffer.markSlotFilled();
    }
}