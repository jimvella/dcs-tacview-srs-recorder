package org.dcstacviewsrsrecorder.lavaplayer;

import com.sedmelluq.discord.lavaplayer.filter.AudioPipeline;
import com.sedmelluq.discord.lavaplayer.filter.AudioPipelineFactory;
import com.sedmelluq.discord.lavaplayer.filter.PcmFormat;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.OpusAudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.transcoder.AudioChunkDecoder;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerOptions;
import com.sedmelluq.discord.lavaplayer.track.playback.AllocatingAudioFrameBuffer;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;
import org.dcstacviewsrsrecorder.opus.OpusFiles;
import org.dcstacviewsrsrecorder.recordingservice.AudioStore;
import org.gagravarr.opus.OpusAudioData;
import uk.me.berndporr.iirj.Butterworth;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

public class LavaFunctions {
    public static final AudioDataFormat SRS_OPUS = new OpusAudioDataFormat(1, 48000, 960);

    public void toFileWithRadioEffect(
            long start,
            long end,
            AudioDataFormat format,
            Stream<AudioStore.Packet> s
    ) {
        Function<ShortBuffer, ShortBuffer> radioEffectFilter = radioEffectFilter();
        File file = decodePaddingWithSilence(
                s,
                start,
                end,
               format,
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
    }

    /*
        Transforms discontiguous, timestamped audio into an audio stream.
     */
    public static <T> T decodePaddingWithSilence(
            Stream<AudioStore.Packet> timestampedPackets,
            long start,
            long end,
            AudioDataFormat audioDataFormat, //format should tell us how to pad with silence
            Consumer<ShortBuffer> filter, //optional transform of the decoded samples
            Function<Stream<AudioFrame>, T> audioFrameConsumer
    ) {
        ShortBuffer shortBuffer = ByteBuffer.allocateDirect(4000).order(ByteOrder.nativeOrder()).asShortBuffer();
        AudioChunkDecoder decoder = audioDataFormat.createDecoder();

        AllocatingAudioFrameBuffer frameBuffer = new AllocatingAudioFrameBuffer(
                60 * 1000, //buffer duration in ms
                audioDataFormat,
                new AtomicBoolean(false) // I think this is to signal stopping
        );
        AudioProcessingContext audioProcessingContext = new AudioProcessingContext(
                new AudioConfiguration(),
                frameBuffer,
                new AudioPlayerOptions(),
                audioDataFormat //output format
        );
        AudioPipeline pipeline = AudioPipelineFactory.create(
                audioProcessingContext,
                new PcmFormat(1, 48000)
        );

        long[] tail = new long[]{ start };

        Stream<AudioFrame> audioFrameStream = timestampedPackets.flatMap(packet -> {
            Stream<AudioFrame> padding = Stream.generate(() -> {
                long pad2 = packet.getTimestamp() - tail[0];
                if(pad2 > 200) { // try to deal with timestamp error / jitter
                    long samples = (packet.getTimestamp() - tail[0]) * 48;
                    long applied = 0;
                    shortBuffer.clear();
                    while (applied < samples && shortBuffer.hasRemaining()) {
                        shortBuffer.put((short) 0);
                        applied++;
                    }
                    tail[0] = tail[0] + (applied/48);
                    try {
                        shortBuffer.flip();
                        pipeline.process(shortBuffer);
                        shortBuffer.clear();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    return Stream.generate(frameBuffer::provide).takeWhile(Objects::nonNull);
                } else {
                    tail[0] = packet.getTimestamp();
                    Stream<AudioFrame> endOfStream = Stream.empty();
                    return endOfStream;
                }
            }).takeWhile(o -> tail[0] < packet.getTimestamp() ).flatMap(s -> s);

            Stream<AudioFrame> decodedStream = Stream.generate(() -> {
                decoder.decode(packet.getBytes(), shortBuffer);
                tail[0] = packet.getTimestamp() + (shortBuffer.limit() - shortBuffer.position()) / 48;
                filter.accept(shortBuffer);
                try {
                    pipeline.process(shortBuffer);
                    return Stream.generate(frameBuffer::provide);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }).flatMap(s -> s).takeWhile(Objects::nonNull);

            return Stream.concat(padding, decodedStream);
        });

        Stream<AudioFrame> endPadding = Stream.generate(() -> {
            long pad = end - tail[0];
            if(pad > 200) { // try to deal with timestamp error / jitter
                long samples = pad * 48;
                long applied = 0;
                shortBuffer.clear();
                while (applied < samples && shortBuffer.hasRemaining()) {
                    shortBuffer.put((short) 0);
                    applied++;
                }
                tail[0] = tail[0] + (applied/48);
                try {
                    shortBuffer.flip();
                    pipeline.process(shortBuffer);
                    shortBuffer.clear();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return Stream.generate(frameBuffer::provide).takeWhile(Objects::nonNull);
            } else {
                tail[0] = end;
                Stream<AudioFrame> endOfStream = Stream.empty();
                return endOfStream;
            }
        }).takeWhile(o -> tail[0] < end ).flatMap(s -> s);

        T result = audioFrameConsumer.apply(Stream.concat(audioFrameStream, endPadding));
        return result;
    }

    //https://github.com/ciribob/DCS-SimpleRadioStandalone/blob/master/DCS-SR-Client/Audio/Providers/RadioFilter.cs
    // IIR (stateful via feedback) filter function
    public static Function<ShortBuffer, ShortBuffer> radioEffectFilter() {
        float BOOST = 1.5f;
        float CLIPPING_MAX = 0.15f;
        float CLIPPING_MIN = -0.15f;
        double noiseGain = 0.05;

        //https://en.wikipedia.org/wiki/Butterworth_filter
        //https://github.com/berndporr/iirj/blob/master/src/main/java/uk/me/berndporr/iirj/Butterworth.java
        Butterworth bp1 = new Butterworth();
        {
            //Configure bandpass
            int order = 64;
            double sampleRate = 48000; //Hz
            double lowCutoff = 560;
            double highCutoff = 3900;
            double centerFrequency = (highCutoff + lowCutoff)/2.0;;
            double widthFrequency = highCutoff - lowCutoff;
            bp1.bandPass(order,sampleRate, centerFrequency, widthFrequency);
        }
        Butterworth bp2 = new Butterworth();
        {
            //Configure bandpass
            int order = 64;
            double sampleRate = 48000; //Hz
            double lowCutoff = 100;
            double highCutoff = 4500;
            double centerFrequency = (highCutoff + lowCutoff)/2.0;;
            double widthFrequency = highCutoff - lowCutoff;
            bp1.bandPass(order,sampleRate, centerFrequency, widthFrequency);
        }

        return shortBuffer -> {
            for(int i = shortBuffer.position(); i < shortBuffer.limit(); i++) {
                double sample = (double) shortBuffer.get(i);
                sample = sample / 32768d; //normalise

                double noise = (Math.random() - 0.5) * 2;
                sample = sample + (noise * noiseGain);

                sample = Double.min(sample, CLIPPING_MAX);
                sample = Double.max(sample, CLIPPING_MIN);

                sample = bp1.filter(sample);
                sample = bp2.filter(sample);

                sample = sample * BOOST;
                sample = sample * 32768d; //un-normalise

                shortBuffer.put(i,  (short) sample);
            }
            return shortBuffer;
        };
    }
}
