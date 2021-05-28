package org.dcstacviewsrsrecorder.recordingservice;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.github.natanbc.lavadsp.timescale.TimescalePcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.OpusAudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.transcoder.AudioChunkDecoder;
import com.sedmelluq.discord.lavaplayer.format.transcoder.AudioChunkEncoder;
import com.sedmelluq.discord.lavaplayer.player.*;
import com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent;
import com.sedmelluq.discord.lavaplayer.player.event.TrackStartEvent;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.NonAllocatingAudioFrameBuffer;
import org.dcstacviewsrsrecorder.lavaplayer.LavaFunctions;
import org.dcstacviewsrsrecorder.opus.OpusFiles;
import org.dcstacviewsrsrecorder.srs.AsyncMonoAdapter;
import org.gagravarr.ogg.OggFile;
import org.gagravarr.opus.OpusAudioData;
import org.gagravarr.opus.OpusFile;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.dcstacviewsrsrecorder.lavaplayer.LavaFunctions.decodePaddingWithSilence;
import static org.dcstacviewsrsrecorder.lavaplayer.LavaFunctions.radioEffectFilter;

// https://discord.com/channels/298054423656005632/598576342921117707/846453806203928597
public class EncodingTest {

    @Test
    public void paddingTest() {
        AudioStore audioStore = new AudioStore();

        long start = audioStore.findAllForFrequency("fjp", "3.05E8", s -> {
            return s.min(Comparator.comparing(p -> p.getTimestamp())).get().getTimestamp();
        });

        long end = audioStore.findAllForFrequency("fjp", "3.05E8", s -> {
            return s.max(Comparator.comparing(p -> p.getTimestamp())).get().getTimestamp();
        });

        System.out.println("Start " + Instant.ofEpochMilli(start));
        System.out.println("End " + Instant.ofEpochMilli(end));

        AudioDataFormat format = LavaFunctions.SRS_OPUS;
        audioStore.findAllForFrequency("fjp", "3.05E8", s -> {
            Function<ShortBuffer, ShortBuffer> radioEffectFilter = radioEffectFilter();
            File file = decodePaddingWithSilence(
                    s,
                    start - 3000,
                    end + 3000,
                    LavaFunctions.SRS_OPUS,
                    radioEffectFilter::apply,
                    audioFrameStream -> {
                        File outFile = new File("paddingTest.ogg");
                        try {
                            outFile.createNewFile(); // if file already exists will do nothing
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        OpusFiles.toFile(
                                outFile,
                                audioFrameStream.map(audioFrame -> new OpusAudioData(audioFrame.getData()))
                        );

                        return outFile;
                    }
            );
            return file;
        });
    }

    //@Test
    public void lavaDecoderEncoder() throws IOException {
        OpusFile opusFile = new OpusFile(new OggFile(new ClassPathResource("sample.ogg").getInputStream()));

        Stream<OpusAudioData> opusAudioDataStream = OpusFiles.toStream(opusFile);

        AudioDataFormat audioDataFormat = new OpusAudioDataFormat(2, 48000, 960);
        AudioChunkDecoder decoder = audioDataFormat.createDecoder();
        AudioChunkEncoder encoder = audioDataFormat.createEncoder(new AudioConfiguration());

        ShortBuffer shortBuffer = ByteBuffer.allocateDirect(4000).order(ByteOrder.nativeOrder()).asShortBuffer();

        Function<ShortBuffer, ShortBuffer> radioEffectFilter = radioEffectFilter();
        Stream<OpusAudioData> processed = opusAudioDataStream
                .map(opusAudioData -> {
                    decoder.decode(opusAudioData.getData(), shortBuffer);
                    return shortBuffer;
                })
                .map(radioEffectFilter::apply)
                .map(sb -> {
                    byte[] b = encoder.encode(sb);
                    System.out.println("b: " + b.length + " " + b[0] + " " + b[1] + " " + b[2]);
                    OpusAudioData result = new OpusAudioData(b);
                    //sb.clear();
                    return result;
                });

        File outFile = new File("lavaDecoderEncoderTest.ogg");
        outFile.createNewFile(); // if file already exists will do nothing
        OpusFiles.toFile(outFile, processed);
    }



    //@Test
    public void lavaplayerTrackToFile() throws IOException {
        final Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logger.setLevel(Level.INFO);

        AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
        playerManager.getConfiguration().setFrameBufferFactory(NonAllocatingAudioFrameBuffer::new);
        AudioSourceManagers.registerRemoteSources(playerManager);
        AudioSourceManagers.registerLocalSource(playerManager);
        AudioPlayer player = playerManager.createPlayer();

        AsyncMonoAdapter<Void> trackCompleted = new AsyncMonoAdapter<>();

        player.addListener(audioEvent -> {
            if (audioEvent instanceof TrackEndEvent) {
                trackCompleted.publish(null);
            }

            if(audioEvent instanceof TrackStartEvent) {

            }
        });

        player.setFilterFactory((track, format, output)->{
            TimescalePcmAudioFilter audioFilter = new TimescalePcmAudioFilter(output, format.channelCount, format.sampleRate);
            audioFilter.setPitchSemiTones(-2);
            audioFilter.setSpeed(0.8);
            //audioFilter.setSpeed(1.5); //1.5x normal speed
            return Collections.singletonList(audioFilter);
        });

        playerManager.loadItem("https://file-examples-com.github.io/uploads/2017/11/file_example_MP3_700KB.mp3", new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack audioTrack) {
                System.out.println("Playing");
                player.playTrack(audioTrack);
            }

            @Override
            public void playlistLoaded(AudioPlaylist audioPlaylist) {

            }

            @Override
            public void noMatches() {
                System.out.println("No matches");
                trackCompleted.publish(null);
            }

            @Override
            public void loadFailed(FriendlyException e) {
                System.out.println("Load failed");
                trackCompleted.publish(null);
            }
        });

        File outFile = new File("lavaplayerTrackToFileTest.ogg");
        outFile.createNewFile(); // if file already exists will do noth
        OpusFiles.toFile(
                outFile,
                Mono.fromCallable(() -> player.provide())
                        .repeatWhenEmpty(s -> s.delayElements(Duration.ofMillis(10)))
                        .repeat()
                        .takeUntilOther(trackCompleted.create())
                        .toStream()
                        .map(f -> new OpusAudioData(f.getData()))
        );
    }
}
