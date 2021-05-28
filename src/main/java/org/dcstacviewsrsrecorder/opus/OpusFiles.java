package org.dcstacviewsrsrecorder.opus;

import org.gagravarr.ogg.OggFile;
import org.gagravarr.ogg.OggPacketWriter;
import org.gagravarr.opus.OpusAudioData;
import org.gagravarr.opus.OpusFile;
import org.gagravarr.opus.OpusInfo;
import org.gagravarr.opus.OpusTags;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class OpusFiles {

    public static void toFile(File file, Stream<OpusAudioData> audioDataStream) {
        OpusInfo info = new OpusInfo();
        info.setNumChannels(1);
        //info.setNumChannels(2);
        info.setSampleRate(48000);
        OpusTags tags = new OpusTags();
        toFile(file, info, tags, audioDataStream);
    }

    public static void toFile(File file, OpusInfo info, OpusTags tags, Stream<OpusAudioData> audioDataStream) {
        try (
                FileOutputStream fileOutputStream = new FileOutputStream(file, false);
                OggFile oggFile = new OggFile(fileOutputStream)
        ) {
            OggPacketWriter w = oggFile.getPacketWriter();
            w.bufferPacket(info.write(), true);
            w.bufferPacket(tags.write(), true);

            long[] totalSamples = new long[]{0};
            audioDataStream.forEach(opusAudioData -> {
                if(opusAudioData.getData().length > 0) {
                    totalSamples[0] = totalSamples[0] + opusAudioData.getNumberOfSamples();
                    opusAudioData.setGranulePosition(totalSamples[0]);
                    w.setGranulePosition(totalSamples[0]);
                    try {
                        w.bufferPacket(opusAudioData.write(), true);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Stream<OpusAudioData> toStream(OpusFile f) {
        return toStream(() -> {
            try {
                return f.getNextAudioPacket();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).filter(p -> p.getData().length > 0);
    }

    /*
        Stream from supplier that uses null to signal the end of the stream
     */
    public static <T> Stream<T> toStream(Supplier<T> s) {
        Iterator<T> i = new Iterator<T>() {
            T next = s.get();

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public T next() {
                T result = next;
                next = s.get();
                return result;
            }
        };

        Stream<T> stream = StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(i, Spliterator.ORDERED),
                false);
        return stream;
    }
}
