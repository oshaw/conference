package publishers;

import utilities.BufferCircular;

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.Arrays;

public class Microphone extends Publisher<byte[]> {
    byte[] bytes;
    int bytesRead;
    TargetDataLine targetDataLine;
    Timer timer;

    public Microphone(AudioFormat audioFormat) {
        try {
            buffer = new BufferCircular<>(new byte[508][16]);
            targetDataLine = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
            targetDataLine.open(audioFormat);
            targetDataLine.start();
            timer = new Timer(1000 / 30, (ActionEvent actionEvent) -> publish());
            timer.start();
        } catch (LineUnavailableException exception) {
            exception.printStackTrace();
        }
    }

    @Override public void publish() {
        bytes = buffer.getAvailableSlot();
        bytesRead = targetDataLine.read(bytes, 0, Math.min(targetDataLine.available(), bytes.length));
        Arrays.fill(bytes, bytesRead, bytes.length, (byte) 0);
        buffer.receive();
    }
}