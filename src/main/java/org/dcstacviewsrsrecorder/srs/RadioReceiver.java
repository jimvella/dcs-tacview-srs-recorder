package org.dcstacviewsrsrecorder.srs;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;
import reactor.netty.Connection;
import reactor.netty.DisposableChannel;
import reactor.netty.tcp.TcpClient;
import reactor.netty.udp.UdpClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
    Connects to an SRS service and listens on some frequencies.
 */
public class RadioReceiver {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final String guid = ShortGuid.encode(UUID.randomUUID().toString());
    private final ObjectMapper mapper;
    {
        mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }
    private final List<Double> frequencies;
    private final  Consumer<UdpVoicePacket> consumer;
    private final Flux<byte[]> outputAudio;

    private volatile Connection connection;

    public RadioReceiver(List<Double> frequencies, Consumer<UdpVoicePacket> consumer) {
        this.frequencies = frequencies;
        this.consumer = consumer;
        outputAudio = Flux.empty();
    }

    public RadioReceiver(List<Double> frequencies, Consumer<UdpVoicePacket> consumer, Flux<byte[]> outputAudio) {
        this.frequencies = frequencies;
        this.consumer = consumer;
        this.outputAudio = outputAudio;
    }

    public String getGuid() {
        return guid;
    }

    public List<Double> getFrequencies() {
        return frequencies;
    }

    public Connection getConnection() {
        return connection;
    }

    public Mono<? extends Connection> connect(String host, int port) {
        Flux<String> messages = Flux.just(
                new NetworkMessage(
                        new NetworkMessage.SRClient(
                                guid,
                                "Test station",
                                null,
                                2,
                                new NetworkMessage.DCSLatLngPosition(
                                        0, 0, 8000
                                )
                        ),
                        NetworkMessage.MessageType.SYNC,
                        null,
                        "1.9.0.0"
                ),
                new NetworkMessage(
                        new NetworkMessage.SRClient(
                                guid,
                                "Test station",
                                new NetworkMessage.DCSPlayerRadioInfo(
                                        "Test Radios",
                                        false,
                                        Stream.of(
                                                frequencies.stream().map(f ->
                                                        NetworkMessage.RadioInformation.DEFAULT
                                                                .withModulation(NetworkMessage.RadioInformation.Modulation.AM)
                                                                .withFrequency(f)
                                                ),
                                                Stream.generate(() ->  NetworkMessage.RadioInformation.DEFAULT)
                                        ).flatMap(s -> s).limit(11).collect(Collectors.toList()),
                                        0,
                                        0,
                                        "DCS Radio Station",
                                        0,
                                        true
                                ),
                                2,
                                new NetworkMessage.DCSLatLngPosition(
                                        0, 0, 8000
                                )
                        ),
                        NetworkMessage.MessageType.RADIO_UPDATE,
                        null,
                        "1.9.0.0"
                )
        ).map(m -> {
            try {
                // Frames delimited by new line
                String message = mapper.writeValueAsString(m) + "\n";
                // logger.warn("Sending: " + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(m));
                return message;
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });

        Flux<ByteBuf> pingFlux = Flux.concat(
                Flux.just(Unpooled.copiedBuffer(guid.getBytes(StandardCharsets.US_ASCII))),
                Flux.generate((Consumer<SynchronousSink<ByteBuf>>)(sink ->
                        sink.next(Unpooled.copiedBuffer(guid.getBytes(StandardCharsets.US_ASCII))))
                ).delayElements(Duration.ofSeconds(10))
        );

        //Connection[] connection = new Connection[1];
        Mono<? extends Connection> udpConnect = UdpClient.create()
                .host(host)
                .port(port)
                .handle((in, out) -> {
                    in.receiveObject()
                            .doOnSubscribe(s -> logger.warn("UDP IN subscribed"))
                            .doOnError(e -> logger.warn("UDP IN error", e))
                            .doAfterTerminate(() -> logger.warn("UDP IN terminated"))
                            //.doOnEach(p -> logger.warn("UDP IN message"))
                            .subscribe(s -> {
                                DatagramPacket p = (DatagramPacket) s;
                                ((DatagramPacket) s).retain();
                                byte[] b = Unpooled.copiedBuffer(p.content()).array();
                                p.release();

                                if (b.length > 22 /* not a udp ping */) {
                                    UdpVoicePacket udpVoicePacket = new UdpVoicePacket(Unpooled.copiedBuffer(p.content()).array());
                                    consumer.accept(udpVoicePacket);
                                }
                            });

                    long unitId = 0;
                    byte[] transmissionGuid = guid.getBytes(StandardCharsets.US_ASCII);
                    byte[] clientGuid = transmissionGuid;
                    Flux<Integer> count = Flux.generate(() -> 1, (state, sink) -> {
                        sink.next(state);
                        return state + 1;
                    });
                    Flux<ByteBuf> outputPackets = outputAudio.zipWith(count)
                            .map(t -> {
                                byte[] audioFrame = t.getT1();
                                //long packetId = t.getT2() * audioFrame.getFormat().chunkSampleCount;
                                long packetId = t.getT2() * 960;
                                long retransmit = 0;

                                UdpVoicePacket voicePacket = new UdpVoicePacket(
                                        audioFrame,
                                        frequencies.stream().map(f -> new UdpVoicePacket.Frequency(f, (byte) 0, (byte) 0)).collect(Collectors.toList()),
                                        unitId,
                                        packetId,
                                        retransmit,
                                        transmissionGuid,
                                        clientGuid
                                );

                                return voicePacket;
                            }).map(p -> Unpooled.copiedBuffer(p.getBytes()));

                    return out.send(pingFlux.mergeWith(outputPackets)
                            .doOnSubscribe(s -> logger.warn("UDP OUT subscribed"))
                            .doOnError(e -> logger.warn("UDP OUT error", e))
                            .doAfterTerminate(() -> logger.warn("UDP OUT terminated"))
                    ).then();
                })
                .connect().doOnSuccess(c -> {
                    logger.warn("UDP connected");
                    connection.onDispose(c); // Try to hook in shutdown;
                    c.onDispose().doAfterTerminate(() -> logger.warn("UDP disconnected")).subscribe();
                });

        Mono<? extends Connection> tcpConnect = TcpClient.create()
                .host(host)
                .port(port)
                .handle((in, out) -> {
                    in.receive().asString()
                            .doOnSubscribe(s -> logger.warn("TCP IN subscribed"))
                            .doOnError(e -> logger.warn("TCP IN error", e))
                            .doAfterTerminate(() -> logger.warn("TCP IN terminated"))
                            .subscribe(o -> {
                                //ObjectMapper mapper = new ObjectMapper();

                                   //Map<String, Object> parsed = mapper.readValue(o, Map.class);
                                    //logger.info("TCP IN Message: " + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed));
                                    logger.info("TCP IN Message: " + o);

                        /* discard */
                    });

                    out.sendString(messages
                            .doOnEach(m -> logger.warn("TCP OUT message"))
                            .doOnSubscribe(s -> logger.warn("TCP OUT subscribed"))
                            .doOnError(e -> logger.warn("TCP OUT error", e))
                            .doAfterTerminate(() -> logger.warn("TCP OUT terminated"))
                    ).then(udpConnect.then()).then().subscribe();

                    return Mono.never();
                })
                .connect()
                .doOnSuccess(c -> {
                    logger.warn("TCP connected");
                    connection = c;
                    c.onDispose().doOnTerminate(() -> logger.warn("TCP disconnected")).subscribe();
                });

        return tcpConnect;
    }

    @Override
    public String toString() {
        return "RadioReceiver{" +
                "frequencies=" + frequencies +
                ", connection=" + Optional.ofNullable(connection).map(DisposableChannel::address).orElse(null) +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RadioReceiver that = (RadioReceiver) o;
        return guid.equals(that.guid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(guid);
    }
}
