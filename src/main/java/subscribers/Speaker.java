package subscribers;

import utilities.PacketAudio;

import javax.sound.sampled.*;

public class Speaker extends Subscriber<PacketAudio> {
    SourceDataLine sourceDataLine;

    public Speaker(AudioFormat audioFormat) {
        try {
            sourceDataLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
            sourceDataLine.open(audioFormat);
            sourceDataLine.start();
        } catch (LineUnavailableException exception) {
            exception.printStackTrace();
        }
    }

    @Override public void receive(PacketAudio packetAudio) {
        sourceDataLine.write(packetAudio.bytes, 0, packetAudio.bytes.length);
    }
}