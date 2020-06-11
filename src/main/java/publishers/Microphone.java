package publishers;

import utilities.ObjectPool;
import utilities.ObjectPoolPacketAudio;
import utilities.PacketAudio;

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.event.ActionEvent;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.Arrays;

public class Microphone extends Publisher<PacketAudio> {
    byte[] bytes;
    int bytesRead;
    ObjectPool<PacketAudio> objectPool = ObjectPoolPacketAudio.getSingleton();
    PacketAudio packetAudio;
    SocketAddress socketAddress;
    TargetDataLine targetDataLine;
    Timer timer;

    public Microphone(AudioFormat audioFormat) {
        try {
            targetDataLine = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
            targetDataLine.open(audioFormat);
            targetDataLine.start();
        } catch (LineUnavailableException exception) {
            exception.printStackTrace();
        }
        run();
    }

    private void run() {
        timer = new Timer(1000 / 30, (ActionEvent actionEvent) -> {
            packetAudio = objectPool.allocate();
            packetAudio.socketAddress = socketAddress;
            packetAudio.instant = Instant.now();
            bytesRead = targetDataLine.read(packetAudio.bytes, 0, Math.min(targetDataLine.available(), bytes.length));
            Arrays.fill(bytes, bytesRead, bytes.length, (byte) 0);
            bufferCircular.put(packetAudio);
        });
        timer.start();
    }
}