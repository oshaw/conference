package publishers;

import utilities.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class Receiver extends Publisher<Packet> {
    DatagramPacket datagramPacket;
    DatagramSocket datagramSocket;
    ObjectPool<PacketAudio> objectPoolPacketAudio = ObjectPoolPacketAudio.getSingleton();
    ObjectPool<PacketVideo> objectPoolPacketVideo = ObjectPoolPacketVideo.getSingleton();
    PacketAudio packetAudio;
    PacketVideo packetVideo;
    Thread thread;

    public Receiver(DatagramSocket datagramSocket) {
        this.datagramSocket = datagramSocket;
        run();
    }

    private void run() {
        thread = new Thread(() -> {
            while (true) {
                try {
                    datagramSocket.receive(datagramPacket);
                    // bufferCircular.put();
                } catch (IOException exception) {
                    exception.printStackTrace();
                }
            }
        });
        thread.start();
    }
}