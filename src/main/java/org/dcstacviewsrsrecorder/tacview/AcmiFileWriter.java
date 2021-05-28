package org.dcstacviewsrsrecorder.tacview;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/*
    Creates and writes to file.

    File naming based on tacview convention from stream metadata.
 */
public class AcmiFileWriter implements AutoCloseable {

    private final Path dir;
    private Path file = null;
    private BufferedWriter writer;
    // Meta data from stream for filename
    private Instant recordingTime = null;
    private String title = null;
    // Stream buffer to collect metadata
    private LinkedList<String> buffer = new LinkedList<>();

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern instantInFileName = Pattern.compile("Tacview-(.*)-DCS.*");

    public static Instant fromFileName(String fileName) {
        Matcher matcher = instantInFileName.matcher(fileName);
        if (matcher.matches()) {
            return LocalDateTime.from(formatter.parse(matcher.group(1))).toInstant(ZoneOffset.UTC);
        } else {
            throw new IllegalStateException("Could not find instant in '" + fileName + "'");
        }
    }

    public static String toFileName(String title, Instant instant) {
        return "Tacview-" + formatter.format(instant.atZone(ZoneId.of("UTC"))) + "-DCS-" + title + ".txt.acmi";
    }

    public AcmiFileWriter(Path dir) {
        this.dir = dir;
    }

    public Path getFile() {
        return file;
    }

    public void accept(List<String> lines) {
        try {
            if(file == null) {
                lines.stream().flatMap(l -> Arrays.stream(l.split("\n"))).map(l -> l + "\n")
                        .filter(l -> l.startsWith("0,RecordingTime=") || l.startsWith("0,Title="))
                        .forEach(l -> {
                            if(l.startsWith("0,RecordingTime=")) {
                                recordingTime = Instant.parse(l.split("=")[1].trim());
                            }
                            if(l.startsWith("0,Title=")) {
                                title = l.split("=")[1].trim();
                            }
                        });

                if(recordingTime != null && title != null) {
                    file = dir.resolve(toFileName(title, recordingTime));
                    Files.createDirectories(dir);
                    file.toFile().createNewFile();
                    writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file.toFile(), false), StandardCharsets.UTF_8));
                    Stream.of(buffer.stream(), lines.stream()).flatMap(s -> s).forEach(l -> {
                        try {
                            writer.write(l);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    writer.flush();
                    buffer = null;
                } else {
                    buffer.addAll(lines);
                }
            } else {
                lines.forEach(l -> {
                    try {
                        writer.write(l);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                writer.flush(); // Flush with reasonable frequency to keep the file up to date for any readers
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception {
        if ( writer != null ) {
            writer.close();
            writer = null;
        }
    }
}
