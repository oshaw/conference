package subscribers;

import utilities.PacketVideo;

import javax.swing.*;
import java.awt.*;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;

public class Window extends Subscriber<PacketVideo> {
    ConcurrentHashMap<SocketAddress, JLabel> socketAddressToJLabel = new ConcurrentHashMap<>();
    GridLayout gridLayout = new GridLayout();
    JFrame jFrame = new JFrame();

    public Window() {
        jFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        jFrame.setVisible(true);
        jFrame.setLayout(gridLayout);
    }

    public void addSocketAddress(SocketAddress socketAddress) {
        socketAddressToJLabel.put(socketAddress, new JLabel());
        socketAddressToJLabel.get(socketAddress).setIcon(new ImageIcon());
        jFrame.add(socketAddressToJLabel.get(socketAddress));
        updateGrid();
    }

    public void removeSocketAddress(SocketAddress socketAddress) {
        jFrame.remove(socketAddressToJLabel.get(socketAddress));
        socketAddressToJLabel.remove(socketAddress);
        updateGrid();
    }

    @Override public void receive(PacketVideo packetVideo) {
        ((ImageIcon) socketAddressToJLabel.get(packetVideo.socketAddress).getIcon())
            .setImage(packetVideo.bufferedImage);
        socketAddressToJLabel.get(packetVideo.socketAddress).repaint();
    }

    void updateGrid() {
        if (0 < socketAddressToJLabel.size()) {
            gridLayout.setColumns((int) Math.ceil(Math.sqrt(socketAddressToJLabel.size())));
            gridLayout.setRows((int) Math.floor(socketAddressToJLabel.size() / gridLayout.getColumns()));
            jFrame.repaint();
        }
    }
}