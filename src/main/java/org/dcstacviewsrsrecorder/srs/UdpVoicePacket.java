package org.dcstacviewsrsrecorder.srs;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

// https://github.com/ciribob/DCS-SimpleRadioStandalone/blob/master/DCS-SR-Common/Network/UDPVoicePacket.cs
public class UdpVoicePacket {

    private final byte[] buffer;

    public UdpVoicePacket(byte[] bytes) {
        this.buffer = bytes;
    }

    public UdpVoicePacket(
            byte[] audioData,
            List<Frequency> frequencies,
            long unitId,
            long packetId,
            long retransmit,
            byte[] transmissionGuid,
            byte[] clientGuid
    ) {
        buffer = new byte[6 /* header bytes*/ + audioData.length + (frequencies.size() * 10) + 57];

        //packet length
        writeBytes(buffer.length, buffer, 0, 2);

        //audio part length
        writeBytes(audioData.length, buffer, 2, 2);

        //frequency part length
        writeBytes(frequencies.size() * 10, buffer, 4, 2);

        //audio part
        writeBytes(audioData, buffer, 6);

        //frequencies part
        {
            int offset = frequencyPartOffset(buffer);
            for (int i = 0; i < frequencies.size(); i++) {
                ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).putDouble(offset + (i *10), frequencies.get(i).getFrequency());
                ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).put(offset + (i *10) + 8, frequencies.get(i).getModulations());
                ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).put(offset + (i *10) + 9, frequencies.get(i).getEncryptions());
            }
        }

        //fixed part
        {
            int offset = fixedSegmentOffset(buffer);
            writeBytes(unitId, buffer, offset, 4);
            writeBytes(packetId, buffer, offset + 4, 8);
            writeBytes(retransmit, buffer, offset + 12, 1);
            writeBytes(transmissionGuid, buffer, offset + 13); // bytes
            writeBytes(clientGuid, buffer, offset + 35); // bytes
        }
    }

    public UdpVoicePacket copyFromBuffer(byte[] buffer) {
        byte[] b = new byte[UdpVoicePacket.packetLength(buffer)];
        System.arraycopy(buffer, 0, b, 0, UdpVoicePacket.audioPartLength(buffer));
        return new UdpVoicePacket(b);
    }

    public byte[] audioData() {
        int length = UdpVoicePacket.audioPartLength(buffer);
        byte[] data = new byte[length];
        System.arraycopy(buffer, 6, data, 0, length);
        return data;
    }

    public byte[] getBytes() {
        return buffer;
    }

    public InputStream getInputStream() {
        return new ByteArrayInputStream(buffer, 0, packetLength());
    }

    public int packetLength() {
        return packetLength(buffer);
    }

    public int audioPartLength() {
        return audioPartLength(buffer);
    }

    public int frequencyPartLength() {
        return frequencyPartLength(buffer);
    }

    public List<Frequency> frequencies() {
        return Frequency.fromPacket(buffer);
    }

    public long unitId() {
        return unitId(buffer);
    }

    public long packetId() {
        return packetId(buffer);
    }

    public long retransmit() {
        return retransmit(buffer);
    }

    public String transmissionGuid() {
        return transmissionGuid(buffer);
    }

    public String clientGuid() {
        return clientGuid(buffer);
    }

    static int packetLength(byte[] buffer) {
       int lsb = buffer[0] & 0xff;
       int msb = buffer[1] & 0xff;
       return (msb << 8) | lsb;
    }

    static boolean isPing(byte[] buffer) {
        return buffer.length <= 22;
    }

    static int audioPartLength(byte[] buffer) {
        int lsb = buffer[2] & 0xff;
        int msb = buffer[3] & 0xff;
        return (msb << 8) | lsb;
    }

    static int frequencyPartLength(byte[] buffer) {
        int lsb = Byte.toUnsignedInt(buffer[4]);
        int msb = Byte.toUnsignedInt(buffer[5]);
        return (msb << 8) | lsb;
    }

    static int frequencyPartOffset(byte[] buffer) {
        return 6 // header bytes
                + audioPartLength(buffer);
    }

    static int fixedSegmentOffset(byte[] buffer) {
        return 6 // header bytes
          + audioPartLength(buffer)
          + frequencyPartLength(buffer);
    }

    public static long fromBytes(byte[] buffer, int offset, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            value += ((long) buffer[i + offset] & 0xffL) << (8 * i);
        }
        return value;
    }

    public static void writeBytes(long value, byte[] buffer, int offset, int length) {
        for (int i = 0; i < length; i++) {
            buffer[i + offset] = (byte) ((value >> (8 * i)) & 0xffL);
        }
    }

    public static void writeBytes(byte[] value, byte[] buffer, int offset) {
        for (int i = 0; i < value.length; i++) {
            buffer[i + offset] = value[i];
        }
    }

    static long unitId (byte[] buffer) {
        return fromBytes(buffer, fixedSegmentOffset(buffer) + 0, 4);
    }

    static long packetId (byte[] buffer) {
        return fromBytes(buffer, fixedSegmentOffset(buffer) + 4, 8);
    }

    static long retransmit (byte[] buffer) {
        return fromBytes(buffer, fixedSegmentOffset(buffer) + 12, 1);
    }

    static String transmissionGuid (byte[] buffer) {
        return new String(buffer, fixedSegmentOffset(buffer) + 13, 22, StandardCharsets.US_ASCII);
    }

    static String clientGuid (byte[] buffer) {
        try {
            return new String(buffer, fixedSegmentOffset(buffer) + 35, 22, StandardCharsets.US_ASCII);
        } catch (IndexOutOfBoundsException e) {
            // Might be a ping message consisting of just the client guid
            return new String(buffer, 0, 22, StandardCharsets.US_ASCII);
        }
    }

    @Override
    public String toString() {
        return "UdpVoicePacket{" +
                "packetLength=" + packetLength() +
                ", audioPartLength=" + audioPartLength() +
                ", frequencyPartLength=" + frequencyPartLength() +
                ", frequencies=" + frequencies() +
                ", unitId=" +unitId() +
                ", packetId=" + packetId() +
                ", retransmit=" + retransmit() +
                ", transmissionGuid=" + transmissionGuid() +
                ", clientGuid=" + clientGuid() +
                '}';
    }

    public static class Frequency {
        final double frequency;
        final byte modulations;
        final byte encryptions;

        public Frequency(double frequency, byte modulations, byte encryptions) {
            this.frequency = frequency;
            this.modulations = modulations;
            this.encryptions = encryptions;
        }

        static List<Frequency> fromPacket(byte[] packet) {
            if(isPing(packet)) return Collections.emptyList();

            List<Frequency> frequencies = new LinkedList<>();
            for(int offset = frequencyPartOffset(packet); offset < frequencyPartOffset(packet) + frequencyPartLength(packet); offset = offset + 10) {
                try {
                    frequencies.add(Frequency.fromPart(packet, offset));
                } catch (Exception e) {
                    //System.out.println("Problem reading freq: " + e.getMessage());
                }
            }
            return frequencies;
        }

        static Frequency fromPart(byte[] packet, int offset) {
            return new Frequency(
                    ByteBuffer.wrap(packet, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble(),
                    packet[offset + 8],
                    packet[offset + 9]
            );
        }

        public double getFrequency() {
            return frequency;
        }

        public byte getModulations() {
            return modulations;
        }

        public byte getEncryptions() {
            return encryptions;
        }

        @Override
        public String toString() {
            return "Frequency{" +
                    "frequency=" + frequency +
                    ", modulations=" + modulations +
                    ", encryptions=" + encryptions +
                    '}';
        }
    }

    private static final byte[] HEX_ARRAY = "0123456789ABCDEF".getBytes();
    public static String bytesToHex(byte[] bytes, int offset, int length) {
        byte[] hexChars = new byte[length * 2];
        for (int j = 0; j < length; j++) {
            int v = bytes[j + offset] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars, StandardCharsets.UTF_8);
    }
}
