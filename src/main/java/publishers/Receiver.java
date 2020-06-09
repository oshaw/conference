package publishers;

import utilities.BufferCircular;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class Receiver extends Publisher<DatagramPacket> {
    DatagramPacket datagramPacket;
    DatagramSocket datagramSocket;
    Thread thread;

    public Receiver(DatagramSocket datagramSocket) {
        this.datagramSocket = datagramSocket;
        buffer = new BufferCircular<>(new DatagramPacket[16]);
        thread = new Thread(() -> {while (true) publish();});
    }

    @Override void publish() {
        try {
            datagramPacket = buffer.getAvailableSlot();
            datagramSocket.receive(datagramPacket);
            buffer.receive();
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }
}