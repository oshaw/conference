package publishers;

import utilities.BufferCircular;
import utilities.Packet;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class Receiver extends Publisher<Packet> {
    DatagramPacket datagramPacket;
    DatagramSocket datagramSocket;
    Packet packet;
    Thread thread;

    public Receiver(DatagramSocket datagramSocket) {
        this.datagramSocket = datagramSocket;
        buffer = new BufferCircular<>(new Packet[16]);
        thread = new Thread(() -> {while (true) publish();});
    }

    @Override void publish() {
        try {
            packet = buffer.getAvailableSlot();
            datagramSocket.receive(datagramPacket);
            buffer.markSlotFilled();
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }
}