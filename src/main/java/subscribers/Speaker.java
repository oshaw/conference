package subscribers;

import javax.sound.sampled.*;

public class Speaker implements Subscriber<byte[]> {
    short bytesAvailable;
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

    @Override public void receive(byte[] bytes) {
        sourceDataLine.write(bytes, 2, (short) (((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF)));
    }

    public void stop() {
        sourceDataLine.drain();
        sourceDataLine.close();
    }
}