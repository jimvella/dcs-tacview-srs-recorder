package org.dcstacviewsrsrecorder.recordingservice;

import org.dcstacviewsrsrecorder.srs.UdpVoicePacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityIterable;
import jetbrains.exodus.entitystore.PersistentEntityStore;
import jetbrains.exodus.entitystore.PersistentEntityStores;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Service
public class AudioStore {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    Cache<String, PersistentEntityStore> stores = Caffeine.newBuilder()
            .maximumSize(100)
            .build();

    private PersistentEntityStore entityStore(String key) {
        // ~/ is needed for linux / AWS environments
        return stores.get(key, k -> PersistentEntityStores.newInstance("~/data/" + key + "/.audio"));
    }

    private final ConcurrentLinkedQueue<KeyAndPacket> toSave;

    public AudioStore() {
        toSave = new ConcurrentLinkedQueue<>();
        new Thread(() ->{
            while(!Thread.currentThread().isInterrupted()) {
                KeyAndPacket keyAndPacket = toSave.poll();
                while (keyAndPacket != null) {
                    save(
                            keyAndPacket.getKey(),
                            keyAndPacket.getPacket()
                    );
                    keyAndPacket = toSave.poll();
                }

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
        }).start();
    }

    public void saveAsync(String key, Packet packet) {
        toSave.offer(new KeyAndPacket(key, packet));
    }

    public void save(String key, Packet packet) {
        entityStore(key).executeInTransaction(txn -> {
            Entity packetEntity = txn.newEntity("packet");
            packetEntity.setProperty("timestamp", packet.getTimestamp());
            // discord channel name or srs frequency
            packetEntity.setProperty("label", packet.getLabel());
            packetEntity.setBlob("bytes", new ByteArrayInputStream(packet.getBytes()));
        });
    }

    public void save(String key, UdpVoicePacket udpVoicePacket) {
        udpVoicePacket.frequencies().forEach(frequency -> save(key, new SimplePacket(
                Instant.now().toEpochMilli(),
                udpVoicePacket.audioData(),
                frequency.getFrequency() + ""
        )));
    }

    public <T> T findAll(String key, Function<Stream<Packet>, T> f) {
        return entityStore(key).computeInReadonlyTransaction(txn -> {
            EntityIterable i = txn.getAll("packet");
            return f.apply(
                    StreamSupport.stream(i.spliterator(), false)
                            .map(EntityPacket::new)
            );
        });
    }

    public <T> T findAllForFrequency(String key, String label, Function<Stream<Packet>, T> f) {
        return entityStore(key).computeInReadonlyTransaction(txn -> {
            EntityIterable i = txn.find("packet", "label", label);
            return f.apply(
                    StreamSupport.stream(i.spliterator(), false)
                            .map(EntityPacket::new)
            );
        });
    }

    public <T> T findAll(String key, Instant from, Instant until, Function<Stream<Packet>, T> f) {
        return entityStore(key).computeInReadonlyTransaction(txn -> {
            EntityIterable i = txn.find("packet", "timestamp", from.toEpochMilli(), until.toEpochMilli());
            return f.apply(
                    StreamSupport.stream(i.spliterator(), false)
                            .map(EntityPacket::new)
            );
        });
    }

    public <T> T findAllForFrequency(String key, String label, Instant from, Instant until, Function<Stream<Packet>, T> f) {
        return entityStore(key).computeInReadonlyTransaction(txn -> {
            EntityIterable i = txn.find("packet", "label", label).intersect(
                    txn.find("packet", "timestamp", from.toEpochMilli(), until.toEpochMilli())
            );
            return f.apply(
                    StreamSupport.stream(i.spliterator(), false)
                            .map(EntityPacket::new)
            );
        });
    }

    static class KeyAndPacket {
        private final String key;
        private final Packet packet;

        public KeyAndPacket(String key, Packet packet) {
            this.key = key;
            this.packet = packet;
        }

        public String getKey() {
            return key;
        }

        public Packet getPacket() {
            return packet;
        }
    }

    public interface Packet {
        // Epoch milli
        long getTimestamp();
        byte[] getBytes();
        String getLabel();
    }

    public static class SimplePacket implements Packet {
        private final long timestamp;
        private final byte[] bytes;
        private final String label;

        public SimplePacket(long timestamp, byte[] bytes, String label) {
            this.timestamp = timestamp;
            this.bytes = bytes;
            this.label = label;
        }

        @Override
        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }

        @Override
        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return "SimplePacket{" +
                    "timestamp=" + timestamp +
                    ", label='" + label + '\'' +
                    ", bytes=" + bytesToHex(bytes) +
                    '}';
        }
    }

    static class EntityPacket implements Packet {
        private final Entity entity;

        public EntityPacket(Entity entity) {
            this.entity = entity;
        }

        public long getTimestamp() {
            return (long) entity.getProperty("timestamp");
        }
        public byte[] getBytes() {
            try(InputStream inputStream = entity.getBlob("bytes")) {
                byte[] data = inputStream.readAllBytes();
                return data;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        public String getLabel() {
            try {
                return (String) entity.getProperty("label");
            } catch (ClassCastException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String toString() {
            return "SimplePacket{" +
                    "timestamp=" + getTimestamp() +
                    ", label=" + getLabel() +
                    ", bytes=" +  bytesToHex(getBytes()) +
                    '}';
        }
    }

    private static final byte[] HEX_ARRAY = "0123456789ABCDEF".getBytes();
    public static String bytesToHex(byte[] bytes) {
        return bytesToHex(bytes, 0, bytes.length);
    }
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
