package org.dcstacviewsrsrecorder.tacview;

import org.dcstacviewsrsrecorder.recordingservice.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class AcmiStreamSplicer {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final Interval<Instant> targetRecordingTime;

    private Instant referenceTime;
    private Instant recordingTime;

    private Map<String, String> headers = new HashMap<>();
    private Map<String, String> globals = new HashMap<>();
    private Map<String, AcmiObject> objects = new HashMap<>();

    private boolean aggregating = true;
    private boolean completed = false;
    private Duration offset = Duration.ZERO; // time from the reference time

    private Duration referenceShift = Duration.ZERO;

    public AcmiStreamSplicer(Interval<Instant> targetRecordingTime) {
        this.targetRecordingTime = targetRecordingTime;
    }

    private String toAcmiHeaderAndState() {

        Stream<String> headerOffset = referenceShift.isNegative() ? Stream.of(
                "#" + ((double) offset.plus(referenceShift).toMillis()) / 1000,
                "0,Event=RecordingTimestamp|" + targetRecordingTime.getStart().toEpochMilli(), //Dummy event to get tacview to render the padded time
                "#" + ((double) offset.toMillis()) / 1000
        ) : Stream.of(
                "#" + ((double) offset.plus(referenceShift).toMillis()) / 1000
        );

        return Stream.of(
                headers.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey)).map(e -> e.getKey() + "=" + e.getValue()),
                Stream.of(
                        Stream.of("0,ReferenceTime=" + DateTimeFormatter.ISO_INSTANT.format(referenceTime)),
                        Stream.of("0,RecordingTime=" + DateTimeFormatter.ISO_INSTANT.format(targetRecordingTime.getStart())),
                        globals.entrySet().stream()
                                .filter(e -> Stream.of("ReferenceTime", "RecordingTime").noneMatch(s -> e.getKey().contentEquals(s)))
                                .map(e -> "0," + e.getKey() + "=" + escape(e.getValue()))
                ).flatMap(s -> s),
                headerOffset,
                objects.values().stream().map(AcmiObject::toAcmi)
        ).flatMap(s -> s).collect(Collectors.joining("\n"));
    }

    static String escape(String s) {
        return s.replaceAll(",", "\\\\,").replaceAll("\n", "\\\\\n");
    }

    private static Map.Entry<String, String> split(String s) {
        int index = s.indexOf("=");
        if(index == -1) {
            throw new IllegalStateException("Failed to split " + s);
        }
        return Map.entry(s.substring(0, index), s.substring(index+1));
    }

    public List<String> apply(String line) {
        if(completed) {
            return List.of();
        }

        if(aggregating) {
            if(line.startsWith("0,Event=RecordingTimestamp|")) {
                recordingTime = Instant.ofEpochMilli(Long.parseLong(line.replace("0,Event=RecordingTimestamp|", "")));
                if(recordingTime.compareTo(targetRecordingTime.getStart()) >= 0) {
                    aggregating = false;
                    referenceShift = Duration.between(recordingTime, targetRecordingTime.getStart());
                    recordingTime = targetRecordingTime.getStart();

                    return Stream.of(
                            Stream.of(toAcmiHeaderAndState()),
                            apply(line).stream()
                    ).flatMap(s -> s).collect(Collectors.toList());
                }
            } else if (line.startsWith("0,Event")) {
                // Event
            } else if (line.startsWith("0,")) {
                // Global
                Map.Entry<String, String> entry = split(line.replace("0,", ""));
                if (entry.getKey().equals("ReferenceTime")) {
                    referenceTime = Instant.parse(entry.getValue());
                    //offset = Duration.ZERO;
                }
                if (entry.getKey().equals("RecordingTime")) {
                    recordingTime = Instant.parse(entry.getValue());
                }
                globals.put(entry.getKey(), entry.getValue());
            } else if (line.startsWith("#")) {
                // Time update
                double seconds = Double.parseDouble(line.replace("#", ""));
                long millis = (long) (seconds * 1000);
                offset = Duration.ofMillis(millis);
                // Expecting this to stay the same for a session
            } else if (line.startsWith("File")) {
                // Header
                Map.Entry<String, String> entry = split(line);
                headers.put(entry.getKey(), entry.getValue());
            } else {
                // Object
                String[] tokens = line.split(",");
                String id = tokens[0];
                AcmiObject acmiObject = objects.computeIfAbsent(id, AcmiObject::new);
                Arrays.stream(tokens).skip(1 /* id */).map(AcmiStreamSplicer::split).forEach(acmiObject::apply);
            }

            return List.of();
        } else {
            if(line.startsWith("0,Event=RecordingTimestamp|")) {
                recordingTime = Instant.ofEpochMilli(Long.parseLong(line.replace("0,Event=RecordingTimestamp|", "")));
                if(recordingTime.isAfter(targetRecordingTime.getEnd())) {
                    completed = true;
                    return List.of();
                } else {
                    return List.of(line);
                }
            } else if(line.startsWith("0,Event")) {
                // Event
                return List.of(line);
            } else if(line.startsWith("0,")) {
                // Global
                Map.Entry<String, String> entry = split(line.replace("0,", ""));
                if (entry.getKey().equals("ReferenceTime")) {
                    Instant newReferenceTime = Instant.parse(entry.getValue());
                    if(newReferenceTime.compareTo(referenceTime) != 0) {
                        // Expecting this to stay the same for a session
                        logger.warn("New reference time: " + newReferenceTime + " Old: " + referenceTime);
                    }
                }
                if (entry.getKey().equals("RecordingTime")) {
//                    recordingTime = Instant.parse(entry.getValue());
//                    offset = Duration.ZERO;
                    return List.of();
                }
                return List.of(line);
            } else if(line.startsWith("#")) {
                return List.of(line);
            } else if(line.startsWith("File")) {
                // Header
                return List.of();
            } else {
                // Object - May need translation if the ref lat logs shift
                return List.of(line);
            }
        }
    }

//    static <T> T fromZip(InputStream is, Function<Stream<String>, T> f) {
//        try (ZipInputStream zipInputStream = new ZipInputStream(is)) {
//            ZipEntry zipEntry = zipInputStream.getNextEntry();
//            try {
//                BufferedReader r = new BufferedReader(new InputStreamReader(zipInputStream));
//                return f.apply(r.lines());
//            } finally {
//                zipInputStream.closeEntry();
//            }
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
//    }

    static class AcmiObject {
        private final String id;
        private Map<String,String> properties = new HashMap<>();
        private Double latitude;
        private Double longitude;
        private Double altitude;
        private Double roll;
        private Double pitch;
        private Double yaw;
        // For flat coordinate sources
        private Double u;
        private Double v;
        private Double heading;

        AcmiObject(String id) {
            this.id = id;
        }

        String toTransform() {
            Stream<Double> s = Stream.empty();
            if(heading != null) { // need complex flat
                s = Stream.of(
                        longitude,
                        latitude,
                        altitude,
                        roll,
                        pitch,
                        yaw,
                        u,
                        v,
                        heading
                );
            } else if(roll != null){ // need complex spherical
                s = Stream.of(
                        longitude,
                        latitude,
                        altitude,
                        roll,
                        pitch,
                        yaw
                );
            } else if(u != null) { // need simple flat
                s = Stream.of(
                        longitude,
                        latitude,
                        altitude,
                        u,
                        v
                );
            } else { // simple spherical
                s = Stream.of(
                        longitude,
                        latitude,
                        altitude
                );
            }

            return "T=" + s.map(v -> v == null ? "" : v.toString()).collect(Collectors.joining("|"));
        }

        String toAcmi() {
            return Stream.of(
                    Stream.of(id),
                    Stream.of(toTransform()),
                    properties.entrySet().stream().map(e -> e.getKey() + "=" + escape(e.getValue()))
            ).flatMap(s -> s).collect(Collectors.joining(","));
        }

        static void parse(String v, Consumer<Double> c) {
            Optional.of(v).filter(s -> !s.isEmpty()).map(Double::valueOf).ifPresent(c::accept);
        }

        void apply(Map.Entry<String, String> property) {
            if(property.getKey().equals("T")) {
                String[] values = property.getValue().split("\\|", -1);
                if(values.length == 3) {
                    //Simple spherical
                    parse(values[0], v -> longitude = v);
                    parse(values[1], v -> latitude = v);
                    parse(values[2], v -> altitude = v);
                } else if(values.length == 5) {
                    // Simple flat
                    parse(values[0], v -> longitude = v);
                    parse(values[1], v -> latitude = v);
                    parse(values[2], v -> altitude = v);
                    parse(values[3], v -> u = v);
                    parse(values[4], i -> v = i);
                } else if(values.length == 6) {
                    // Complex spherical
                    parse(values[0], v -> longitude = v);
                    parse(values[1], v -> latitude = v);
                    parse(values[2], v -> altitude = v);
                    parse(values[3], v -> roll = v);
                    parse(values[4], v -> pitch = v);
                    parse(values[5], v -> yaw = v);
                } else if(values.length == 9) {
                    // Complex flat
                    parse(values[0], v -> longitude = v);
                    parse(values[1], v -> latitude = v);
                    parse(values[2], v -> altitude = v);
                    parse(values[3], v -> roll = v);
                    parse(values[4], v -> pitch = v);
                    parse(values[5], v -> yaw = v);
                    parse(values[6], v -> u = v);
                    parse(values[7], i -> v = i);
                    parse(values[8], i -> heading = i);
                } else {
                    throw new IllegalStateException("Can't parse " + property + " ... " + values.length);
                }
            } else {
                properties.put(property.getKey(), property.getValue());
            }
        }

        @Override
        public String toString() {
            return toAcmi();
        }
    }
}

