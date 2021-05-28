package org.dcstacviewsrsrecorder.tacview;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.tcp.TcpClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalField;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
    Records from a real time tacview service
 */
public class TacviewRecorder {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final Path dir;

    public TacviewRecorder(Path dir) {
        this.dir = dir;
    }

    public Flux<Void> connect(String host, int port) {
        TemporalField temporalField = ChronoField.MINUTE_OF_HOUR;
        long period = 20;
        Flux<Instant> scheduledTimes = Flux.generate(
                Instant::now,
                (state, sink) -> {
                    sink.next(state);
                    return state.plus(
                            period - (state.atZone(ZoneId.of("UTC")).getLong(temporalField) % period),
                            temporalField.getBaseUnit()
                    ).truncatedTo(temporalField.getBaseUnit());
                }
        );

        Flux<? extends Connection> flux = scheduledTimes.zipWith(scheduledTimes.skip(1))
                .delayUntil(interval ->
                        Instant.now().isBefore(interval.getT1()) ? Mono.delay(Duration.between(Instant.now(), interval.getT1())) : Mono.empty()
                )
                // Skip if its going to be short
                .filter(interval -> Duration.between(interval.getT1(), interval.getT2()).compareTo(Duration.ofSeconds(5)) > 0)
                .flatMapSequential(interval -> {
                    TcpClient tcpClient = TcpClient.create()
                            .host(host)
                            .port(port)
                            .doOnConnected(c -> logger.info("Connected to " + host + ":" + port))
                            .doOnDisconnected(c -> logger.info("Disconnected from " + host + ":" + port))
                            .doOnConnected(c -> c.addHandlerFirst("codec", new DelimiterBasedFrameDecoder(
                                    8 * 1024,
                                    false,
                                    Stream.of(
                                            Delimiters.lineDelimiter(),
                                            Delimiters.nulDelimiter()
                                    ).flatMap(Arrays::stream).collect(Collectors.toList()).toArray(ByteBuf[]::new)
                            )))
                            .handle((inbound, outbound) -> {
                                AcmiFileWriter acmiFileWriter = new AcmiFileWriter(dir);

                                Disposable fileHandler = inbound.receiveObject().map(o -> {
                                    ByteBuf byteBuf = (ByteBuf) o;
                                    return byteBuf.toString(StandardCharsets.UTF_8);
                                })
                                        .skipUntil(s -> s.contains("FileType")) /* Marks the start of the stream / end of the header */
                                        .flatMapSequential(frame -> {
                                            if (frame.startsWith("#")) { //time-frame
                                                return Flux.just(
                                                        /*
                                                            Ideally added by server / recorder
                                                            Channel delay not accounted for.
                                                         */
                                                        frame,
                                                        "0,Event=RecordingTimestamp|" + Instant.now().toEpochMilli() + "\n"
                                                );
                                            } else if (frame.startsWith("0,RecordingTime=")) {
                                                // Use local clock instead of server clock - need to correlate with locally saved audio
                                                return Mono.just("0,RecordingTime=" + DateTimeFormatter.ISO_INSTANT
                                                        .format(Instant.now().truncatedTo(ChronoUnit.MILLIS)) + "\n"
                                                );
                                            } else {
                                                return Mono.just(frame);
                                            }
                                        })
                                        .buffer(Duration.ofMillis(250)) //try to save disk io
                                        .doAfterTerminate(() -> {
                                            logger.info("Closing file " + dir);
                                            try {
                                                acmiFileWriter.close();
                                            } catch (Exception e) {
                                                throw new RuntimeException(e);
                                            }
                                        })
//                                        .map(lines -> {
//                                            acmiFileWriter.accept(lines);
//                                            return true;
//                                        });
                                        .subscribe(acmiFileWriter::accept);
                                inbound.withConnection(connection -> connection.onDispose(fileHandler));

                                byte[] nulDelimiter = new byte[]{0};
                                String message = "XtraLib.Stream.0\n" +
                                        "Tacview.RealTimeTelemetry.0\n" +
                                        "Jim\n" +
                                        "0";
                                ByteBuf toSend = Unpooled.copiedBuffer(message.getBytes(StandardCharsets.UTF_8), nulDelimiter);
                                outbound.sendObject(Flux.just(toSend)).then().subscribe();

                                return Mono.delay(Duration.between(Instant.now(), interval.getT2())).then();
//                                return f.then().timeout(Duration.between(Instant.now(), interval.getT2()))
//                                        .onErrorResume(t -> Mono.empty());
                            });

                    return tcpClient.connect();
                });
        Flux<Void> voidFlux = flux.flatMap(connection -> {
            return Flux.using(
                    () -> {
                        logger.info("Supplying connection");
                        return connection;
                    },
                    c -> {
                        return c.onDispose();
                    },
                    c -> {
                        logger.info("Disposing connection");
                        c.dispose();
                    }
            );
        });

        return voidFlux;
    }
}
