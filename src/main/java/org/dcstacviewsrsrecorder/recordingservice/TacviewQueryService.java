package org.dcstacviewsrsrecorder.recordingservice;

import org.dcstacviewsrsrecorder.tacview.AcmiFileWriter;
import org.dcstacviewsrsrecorder.tacview.AcmiStreamSplicer;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Scanner;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class TacviewQueryService {

    public List<Interval<Instant>> intervals(Path acmiDataDir, Instant from, Instant until) {
        try {
            Interval<Instant> interval = Interval.between(
                    from,
                    until
            );
            Path dir = acmiDataDir;

            List<Interval<Instant>> intervals = Flux.fromStream(Files.find(dir, 1, ((path, basicFileAttributes) -> basicFileAttributes.isRegularFile())).sorted())
                    .zipWith(Flux.fromStream(Files.find(dir, 1, ((path, basicFileAttributes) -> basicFileAttributes.isRegularFile())).sorted())
                            .skip(1).concatWith(Mono.just(Path.of(AcmiFileWriter.toFileName("", Instant.now())))))
                    .map(files -> {
                        return Interval.between(
                                AcmiFileWriter.fromFileName(files.getT1().getFileName().toString()),
                                AcmiFileWriter.fromFileName(files.getT2().getFileName().toString())
                        );
                    })
                    .filter(i -> {
                        return i.intersects(interval);
                    }).toStream().collect(Collectors.toList());

            return intervals;
        } catch (NoSuchFileException e) {
          return List.of();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // TODO - cache
    public Path query(Path acmiDataDir, Instant from, Instant until, String zipEntryFilename) {

        Interval<Instant> interval = Interval.between(
                from,
                until
        );

        try {
            Path dir = acmiDataDir;
            List<Path> filesToQuery = Flux.fromStream(Files.find(dir, 1, ((path, basicFileAttributes) -> basicFileAttributes.isRegularFile())).sorted())
                    .zipWith(Flux.fromStream(Files.find(dir, 1, ((path, basicFileAttributes) -> basicFileAttributes.isRegularFile())).sorted())
                            .skip(1).concatWith(Mono.just(Path.of(AcmiFileWriter.toFileName("", Instant.now())))))
                    .filter(files -> {
                        Interval<Instant> i = Interval.between(
                                AcmiFileWriter.fromFileName(files.getT1().getFileName().toString()),
                                AcmiFileWriter.fromFileName(files.getT2().getFileName().toString())
                        );
                        return i.intersects(interval);
                    }).map(Tuple2::getT1).toStream().collect(Collectors.toList());

            Path temp = Files.createTempFile("tacview","");

            try(
                    OutputStream os = Files.newOutputStream(temp);
                    ZipOutputStream zos = new ZipOutputStream(os);
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(zos))
            ) {
                ZipEntry zipEntry = new ZipEntry(zipEntryFilename);
                zos.putNextEntry(zipEntry);

                AcmiStreamSplicer acmiStreamSplicer = new AcmiStreamSplicer(interval);
                filesToQuery.stream().flatMap(p -> {
                    try {
                        FileInputStream is = new FileInputStream(p.toFile());
                        Scanner scanner = new Scanner(new InputStreamReader(is, StandardCharsets.UTF_8));
                        scanner.useDelimiter("(?<!\\\\)\\n");

                        return streamScanner(scanner);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                        .flatMap(l -> acmiStreamSplicer.apply(l).stream().map(i -> i + "\n"))
                        .forEach(l -> {
                            try {
                                writer.write(l);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });

                writer.flush();
            }

            return temp;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Stream<String> streamScanner(final Scanner scanner) {
        final Spliterator<String> splt = Spliterators.spliterator(scanner, Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL);
        return StreamSupport.stream(splt, false)
                .onClose(scanner::close);
    }
}
