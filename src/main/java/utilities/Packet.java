package utilities;

import java.net.SocketAddress;
import java.time.Instant;

public abstract class Packet {
    public Instant instant;
    public SocketAddress socketAddress;
}