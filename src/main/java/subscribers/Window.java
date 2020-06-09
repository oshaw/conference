package subscribers;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.DatagramPacket;
import java.util.concurrent.ConcurrentHashMap;

public class Window extends Subscriber<DatagramPacket> {
    ConcurrentHashMap<String, JLabel> addressToJLabel = new ConcurrentHashMap<>();
    GridLayout gridLayout = new GridLayout();
    JFrame jFrame = new JFrame();

    public Window() {
        jFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        jFrame.setVisible(true);
        jFrame.setLayout(gridLayout);
    }

    public void addVideo(String address) {
        addressToJLabel.put(address, new JLabel());
        addressToJLabel.get(address).setIcon(new ImageIcon());
        jFrame.add(addressToJLabel.get(address));
        updateGrid();
    }

    public void removeVideo(String address) {
        jFrame.remove(addressToJLabel.get(address));
        addressToJLabel.remove(address);
        updateGrid();
    }

    @Override public void receive(DatagramPacket datagramPacket) {
        BufferedImage bufferedImage = new BufferedImage(); // datagramPacket.getData()
        String address = datagramPacket.getSocketAddress().toString();
        ((ImageIcon) addressToJLabel.get(address).getIcon()).setImage(bufferedImage);
        addressToJLabel.get(address).repaint();
    }

    void updateGrid() {
        if (0 < addressToJLabel.size()) {
            gridLayout.setColumns((int) Math.ceil(Math.sqrt(addressToJLabel.size())));
            gridLayout.setRows((int) Math.floor(addressToJLabel.size() / gridLayout.getColumns()));
            jFrame.repaint();
        }
    }
}