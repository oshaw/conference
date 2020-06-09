import publishers.Camera;
import publishers.Microphone;
import publishers.Receiver;
import subscribers.Sender;
import subscribers.Speaker;
import subscribers.Window;

import javax.sound.sampled.AudioFormat;
import java.awt.*;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Client {
    AudioFormat audioFormat = new AudioFormat(8000.0f, 16, 1, true, true);
    Dimension dimension = new Dimension(320, 240);

    Camera camera;
    Microphone microphone;
    Speaker speaker;
    Window window;

    DatagramSocket datagramSocket;
    Set<InetSocketAddress> destinations;
    Receiver receiver;
    Sender sender;

    public Client(InetSocketAddress source, Set<InetSocketAddress> destinations) {
        try {
            this.destinations = destinations;

            microphone = new Microphone(audioFormat);
            camera = new Camera(dimension);
            speaker = new Speaker(audioFormat);
            window = new Window();

            datagramSocket = new DatagramSocket(source);
            receiver = new Receiver(datagramSocket);
            sender = new Sender(datagramSocket, destinations);

            camera.addSubscriber(sender);
            camera.addSubscriber(window);
            microphone.addSubscriber(sender);
            receiver.addSubscriber(speaker);
            receiver.addSubscriber(window);
        } catch (SocketException exception) {
            exception.printStackTrace();
        }
    }

    public static void main(String[] arguments) {
        InetSocketAddress[] inetSocketAddresses = {
            new InetSocketAddress("localhost", 20000),
            new InetSocketAddress("localhost", 20001),
            new InetSocketAddress("localhost", 20002),
        };

        Client[] clients = {
            new Client(inetSocketAddresses[0], new HashSet<InetSocketAddress>(Arrays.asList(inetSocketAddresses[1], inetSocketAddresses[2]))),
            new Client(inetSocketAddresses[1], new HashSet<InetSocketAddress>(Arrays.asList(inetSocketAddresses[0], inetSocketAddresses[2]))),
            new Client(inetSocketAddresses[2], new HashSet<InetSocketAddress>(Arrays.asList(inetSocketAddresses[0], inetSocketAddresses[1])))
        };

        while (true);
    }
}