package org.dcstacviewsrsrecorder.srs;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.DisposableChannel;
import reactor.netty.tcp.TcpClient;
import reactor.netty.udp.UdpClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/*
    Connects to an SRS server and plays media
 */
public class RadioStation {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    final AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
    {
        AudioSourceManagers.registerRemoteSources(playerManager);
        AudioSourceManagers.registerLocalSource(playerManager);
    }

    private final String guid = ShortGuid.encode(UUID.randomUUID().toString());
    private final ObjectMapper mapper;
    {
        mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    // Lavaplayer implementation
    // https://github.com/sedmelluq/lavaplayer
    public Mono<? extends Connection> play(String identifier, String host, int port, double frequency) {
        AudioPlayer player = playerManager.createPlayer();

        AsyncMonoAdapter<Void> terminatedMonoAdapter = new AsyncMonoAdapter<>();
        player.addListener(audioEvent -> {
            if(audioEvent instanceof TrackEndEvent) {
                terminatedMonoAdapter.publish(null);
            }
        });

        Mono<Void> terminated = terminatedMonoAdapter.create()
                .doOnTerminate(player::destroy);

        Future<Void> loadItem = playerManager.loadItem(identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack audioTrack) {
                player.setVolume(60); //seems to not get distorted on SRS
                player.playTrack(audioTrack);
            }

            @Override
            public void playlistLoaded(AudioPlaylist audioPlaylist) {

            }

            @Override
            public void noMatches() {
                terminatedMonoAdapter.publish(null);
            }

            @Override
            public void loadFailed(FriendlyException e) {
                terminatedMonoAdapter.publish(null);
            }
        });
        try {
            loadItem.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }

        Flux<AudioFrame> f = Mono.fromCallable(() -> player.provide(0, TimeUnit.SECONDS))
                .repeatWhenEmpty(s -> s.delayElements(Duration.ofMillis(10)))
                .repeat()
                .takeUntilOther(terminated);

        Flux<Integer> count = Flux.generate(() -> 1, (state, sink) -> {
            sink.next(state);
            return state + 1;
        });

        long unitId = 0;
        byte[] transmissionGuid = guid.getBytes(StandardCharsets.US_ASCII);
        byte[] clientGuid = transmissionGuid;

        Instant start = Instant.now();

        Flux<ByteBuf> packetFlux = f.zipWith(count)
                .delayUntil(t -> {
                    Instant scheduled = start.plusMillis(
                            t.getT2() * t.getT1().getFormat().chunkSampleCount / (t.getT1().getFormat().sampleRate / 1000)
                    );
                    Duration duration = Duration.between(Instant.now(), scheduled);
                    return duration.isNegative() ? Mono.empty() : Mono.just(t).delayElement(duration);
                })
                .map(t -> {
                    AudioFrame audioFrame = t.getT1();
                    long packetId = t.getT2() * audioFrame.getFormat().chunkSampleCount;
                    long retransmit = 0;

                    UdpVoicePacket voicePacket = new UdpVoicePacket(
                            audioFrame.getData(),
                            List.of(new UdpVoicePacket.Frequency(
                                    frequency,
                                    (byte) 0,
                                    (byte) 0
                            )),
                            unitId,
                            packetId,
                            retransmit,
                            transmissionGuid,
                            clientGuid
                    );

                    return voicePacket;
                })
                .map(p -> Unpooled.copiedBuffer(p.getBytes()));

        Mono<? extends Connection> udpConnect = UdpClient.create()
                .host(host)
                .port(port)
                .handle((in, out) -> {
                    in.receive().asString().subscribe(o -> {
                        // Not expected for a broadcast
                        logger.warn("Received: ...");
                    });
                    return out.send(packetFlux).then();
                })
                .connect();

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
                                        List.of(
                                                NetworkMessage.RadioInformation.DEFAULT,
                                                NetworkMessage.RadioInformation.DEFAULT,
                                                NetworkMessage.RadioInformation.DEFAULT,
                                                NetworkMessage.RadioInformation.DEFAULT,
                                                NetworkMessage.RadioInformation.DEFAULT,
                                                NetworkMessage.RadioInformation.DEFAULT,
                                                NetworkMessage.RadioInformation.DEFAULT,
                                                NetworkMessage.RadioInformation.DEFAULT,
                                                NetworkMessage.RadioInformation.DEFAULT,
                                                NetworkMessage.RadioInformation.DEFAULT,
                                                NetworkMessage.RadioInformation.DEFAULT
                                        ),
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
                return message;
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });

        Mono<? extends Connection> tcpConnect = TcpClient.create()
                .host(host)
                .port(port)
                .handle((in, out) -> {
                    in.receive().asString().subscribe(o -> {
                        /* discard */
                    });

                    return out.sendString(messages).then(udpConnect.flatMap(DisposableChannel::onDispose));
                }).connect();
        return tcpConnect;
    }
}
