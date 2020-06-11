package publishers;

import org.openimaj.image.ImageUtilities;
import org.openimaj.video.capture.VideoCapture;
import org.openimaj.video.capture.VideoCaptureException;
import utilities.ObjectPool;
import utilities.ObjectPoolPacketVideo;
import utilities.PacketVideo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.net.SocketAddress;
import java.time.Instant;

public class Camera extends Publisher<PacketVideo> {
    int framesPerSecond = 30;
    PacketVideo packetVideo;
    ObjectPool<PacketVideo> objectPool = ObjectPoolPacketVideo.getSingleton();
    SocketAddress socketAddress;
    Timer timer;
    VideoCapture videoCapture;

    public Camera(Dimension dimension) {
        super(ObjectPoolPacketVideo.getSingleton());
        try {
            videoCapture = new VideoCapture((int) dimension.getWidth(), (int) dimension.getHeight());
        } catch (VideoCaptureException exception) {
            exception.printStackTrace();
        }
        run();
    }

    private void run() {
        timer = new Timer(1000 / framesPerSecond, (ActionEvent actionEvent) -> {
            packetVideo = objectPool.allocate();
            packetVideo.socketAddress = socketAddress;
            packetVideo.instant = Instant.now();
            ImageUtilities.createBufferedImage(videoCapture.getNextFrame(), packetVideo.bufferedImage);
            bufferCircular.put(packetVideo);
        });
        timer.start();
    }
}