package org.dcstacviewsrsrecorder.recordingservice.web;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import org.dcstacviewsrsrecorder.lavaplayer.LavaFunctions;
import org.dcstacviewsrsrecorder.opus.OpusFiles;
import org.dcstacviewsrsrecorder.recordingservice.AudioStore;
import org.dcstacviewsrsrecorder.recordingservice.TacviewQueryService;
import org.dcstacviewsrsrecorder.srs.RadioReceiver;
import org.dcstacviewsrsrecorder.tacview.TacviewRecorder;
import org.gagravarr.opus.OpusAudioData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.Disposable;
import reactor.netty.Connection;

import java.io.File;
import java.io.IOException;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.dcstacviewsrsrecorder.lavaplayer.LavaFunctions.decodePaddingWithSilence;
import static org.dcstacviewsrsrecorder.lavaplayer.LavaFunctions.radioEffectFilter;

@Controller
public class RecorderController {
    private final Logger logger = LoggerFactory.getLogger(RecorderController.class);

    @Autowired
    AudioStore audioStore;

    @Autowired
    TacviewQueryService tacviewQueryService;

    DateTimeFormatter googleChartFormatter = DateTimeFormatter.ofPattern("'Date('yyyy, MM, dd, HH, mm, ss, SSS')'");

    Map<String, Set<RadioReceiver>> byKey = new HashMap<>();
    private final Map<String, Disposable> tacviewByKey = new HashMap<>();

    @PostMapping("/record/{id}/srs")
    public String addRecorder(
            @PathVariable String id,
            @RequestParam String host,
            @RequestParam String port,
            @RequestParam String frequency
    ) {
        logger.warn("Adding recorder: " + host + " " + port + " " + frequency);

        RadioReceiver radioReceiver = new RadioReceiver(
                Arrays.stream(frequency.split(",")).map(Double::parseDouble).collect(Collectors.toList())
                , p -> audioStore.save(id, p)
        );
        byKey.computeIfAbsent(id, i -> new HashSet<>()).add(radioReceiver);
        radioReceiver.connect(
                host,
                Integer.parseInt(port)
        ).doOnError(e -> byKey.computeIfAbsent(id, i -> new HashSet<>()).remove(radioReceiver)).block();

        return "redirect:/record/" + id;
    }

    @PostMapping("/record/{id}/srs/{guid}")
    public String removeRecorder(
            @PathVariable String id,
            @PathVariable String guid
    ) {
        logger.warn("Deleting recorder: " + guid);

        byKey.getOrDefault(id, Set.of()).stream().filter(r -> r.getGuid().equals(guid)).findFirst().ifPresent(radioReceiver -> {
            radioReceiver.getConnection().dispose();
            byKey.get(id).remove(radioReceiver);
        });
        return "redirect:/record/" + id;
    }

    @GetMapping("/record/{id}")
    public String chart(
            @PathVariable String id,
            @RequestParam Optional<String> from,
            @RequestParam Optional<String> until,
            Model model
    ) {
        if(from.isEmpty() || until.isEmpty()) {
            String uri = UriComponentsBuilder.fromPath("/record/" + id)
                        .queryParam("from", AviationDateTimeFormat.format(Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS)))
                        .queryParam("until", AviationDateTimeFormat.format(Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS)))
                        .build().toUriString();

            return "redirect:" + uri;
        }

        model.addAttribute("baseUrl", "/record/" + id);

        List<String> labels = audioStore.findAll(id, AviationDateTimeFormat.parse(from.get()), AviationDateTimeFormat.parse(until.get()), s -> s.map(AudioStore.Packet::getLabel).distinct().collect(Collectors.toList()));
        Map<String, List<Interval>> byLabel = labels.stream()
                .map(label -> audioStore.findAllForFrequency(id, label, AviationDateTimeFormat.parse(from.get()), AviationDateTimeFormat.parse(until.get()), s ->
                        Map.entry(
                                label,
                                s.map(packet -> new Interval(
                                        Instant.ofEpochMilli(packet.getTimestamp()),
                                        Instant.ofEpochMilli(packet.getTimestamp() + 40)) //packets are generally 40ms
                                ).collect(mergeIntervals())
                        )
                )).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/record/" + id + "/download/" + id + "_{frequency}_" + from.get() + "_" + until.get() + ".ogg");

        model.addAttribute(
                "receivers", byKey.getOrDefault(id, Set.of())
                        .stream().sorted(Comparator.comparing(r -> r.getFrequencies().get(0))).collect(Collectors.toList())
        );

        String tacviewDownloadLink = "/record/" + id + "/download/Tacview-" + from.get() + "-" + until.get() + "-" + id + ".zip.acmi";
        Stream<Map<String, List<Map<String, ?>>>> tacviewIntervals = tacviewQueryService.intervals(
                Path.of("data/" + id + "/acmi"),
                AviationDateTimeFormat.parse(from.get()),
                AviationDateTimeFormat.parse(until.get())
        ).stream().map(interval -> Map.of("c", List.of(
                Map.of("v", "Tacview", "p", Map.of("link", tacviewDownloadLink)),
                Map.of("v", googleChartFormatter.format(interval.getStart().atZone(ZoneId.of("UTC")))),
                Map.of("v", googleChartFormatter.format(interval.getEnd().atZone(ZoneId.of("UTC"))))
        )));

        Stream<Map<String, List<Map<String, ?>>>> audioIntervals = byLabel.entrySet().stream().flatMap(e -> {
            String label = e.getKey();
            List<Interval> intervals = e.getValue();

            return intervals.stream().map(interval -> Map.of(
                    "c", List.of(
                            Map.of("v", "" + label, "p", Map.of("link", builder.build(label).toString())),
                            Map.of("v", googleChartFormatter.format(interval.getStart().atZone(ZoneId.of("UTC")))),
                            Map.of("v", googleChartFormatter.format(interval.getEnd().atZone(ZoneId.of("UTC"))))
                    )
            ));
        });

        model.addAttribute("data", Stream.of(
                tacviewIntervals,
                audioIntervals)
                .flatMap(s -> s).collect(Collectors.toList())
        );
        model.addAttribute("from",  googleChartFormatter.format(AviationDateTimeFormat.parse(from.get()).atZone(ZoneId.of("UTC"))));
        model.addAttribute("until",  googleChartFormatter.format(AviationDateTimeFormat.parse(until.get()).atZone(ZoneId.of("UTC"))));

        model.addAttribute("fromAv",  from.get());
        model.addAttribute("untilAv",  until.get());
        model.addAttribute("id",  id);

        Optional.ofNullable(tacviewByKey.get(id)).ifPresent(disposable -> {
            model.addAttribute("tacviewRecorder", disposable);
        });

        return "chart";
    }

    @GetMapping(value = "/record/{id}/download/{id}_{label}_{from}_{until}.ogg", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<Resource> download(
            @PathVariable String id,
            @PathVariable String label,
            @PathVariable("from") String fromString,
            @PathVariable("until") String untilString
    ) {
        Instant from = AviationDateTimeFormat.parse(fromString);
        Instant until = AviationDateTimeFormat.parse(untilString);

        Resource resource = audioStore.findAllForFrequency(id, label, from, until, s -> {
            try {
                //TODO - may need to manually delete
                File outFile = File.createTempFile("ogg", null);

                //https://tacview.fandom.com/wiki/Synchronized_Audio/Video_Playback
                logger.warn("Starting ogg export");

                if(label.startsWith("discord")) {
                    Function<ShortBuffer, ShortBuffer> radioEffectFilter = radioEffectFilter();
                    decodePaddingWithSilence(
                            s,
                            from.toEpochMilli(),
                            until.toEpochMilli(),
                            StandardAudioDataFormats.DISCORD_OPUS,
                            radioEffectFilter::apply,
                            audioFrameStream -> {
                                OpusFiles.toFile(
                                        outFile,
                                        audioFrameStream.map(audioFrame -> new OpusAudioData(audioFrame.getData()))
                                );

                                return null;
                            }
                    );

                    return new FileSystemResource(outFile);
                } else {
                    Function<ShortBuffer, ShortBuffer> radioEffectFilter = radioEffectFilter();
                    decodePaddingWithSilence(
                            s,
                            from.toEpochMilli(),
                            until.toEpochMilli(),
                            LavaFunctions.SRS_OPUS,
                            radioEffectFilter::apply,
                            audioFrameStream -> {
                                OpusFiles.toFile(
                                        outFile,
                                        audioFrameStream.map(audioFrame -> new OpusAudioData(audioFrame.getData()))
                                );
                                return null;
                            }
                    );

                    return new FileSystemResource(outFile);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        return ResponseEntity.ok(resource);
    }

    @GetMapping(value = "/record/{id}/download/Tacview-{from:[0-9]+}-{until:[0-9]+}-{id}.zip.acmi", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<Resource> downloadTacview(
            @PathVariable String id,
            @PathVariable("from") String fromString,
            @PathVariable("until") String untilString
    ) {
        Instant from = AviationDateTimeFormat.parse(fromString);
        Instant until = AviationDateTimeFormat.parse(untilString);

        String fileName = "Tacview-" + fromString + "-" + untilString + "-" + id + ".zip.acmi";
        String zipEntryFilename = fileName.replace(".zip.acmi", ".txt.acmi");

        Path path = tacviewQueryService.query(Path.of("data/" + id + "/acmi"), from, until, zipEntryFilename);

        return ResponseEntity.ok(new FileSystemResource(path));
    }

    @PostMapping("/record/{id}/tacview/cancel")
    public String cancelTacview(@PathVariable String id) {
        logger.info("Cancelling tacview for " + id);
        Optional.ofNullable(tacviewByKey.remove(id)).ifPresent(disposable -> {
            logger.info("Disposing tacview recorder subscription");
            disposable.dispose();
        });

        return "redirect:/record/" + id;
    }

    @PostMapping("/record/{id}/tacview")
    public String setRecorder(
            @PathVariable String id,
            @RequestParam String host,
            @RequestParam String port
    ) {
        if(!tacviewByKey.containsKey(id)) {
            logger.warn("Setting recorder: " + host + " " + port + " for " + id);
            TacviewRecorder recorder = new TacviewRecorder(Path.of("data/" + id + "/acmi"));

            Connection[] lastConnection = new Connection[1];
            tacviewByKey.put(
                    id,
                    recorder.connect(host, Integer.parseInt(port)).subscribe()
            );
        } else {
            logger.warn("Recorder already set");
        }

        return "redirect:/record/" + id;
    }

    @GetMapping("/record")
    public String index() {
        return "record/index";
    }

    @PostMapping("/record")
    public String createOrFindDatabase(@RequestParam String name) {
        return "redirect:/record/" + name;
    }

    public static class Interval {
        private final Instant start;
        private final Instant end;

        public Interval(Instant start, Instant end) {
            this.start = start;
            this.end = end;
        }

        public Instant getStart() {
            return start;
        }

        public Instant getEnd() {
            return end;
        }
    }

    public static Collector<Interval, ?, List<Interval>> mergeIntervals() {
        Collector<Interval, ?, List<Interval>> collector = Collector.of(
                () -> new LinkedList<Interval>(),
                (deque, next) ->  {
                    Interval last = deque.peekLast();
                    int tolerance = 200;
                    if(last != null && last.getEnd().toEpochMilli() >= next.getStart().toEpochMilli() - tolerance) {
                        deque.pollLast();
                        deque.add(new Interval(last.getStart(), Instant.ofEpochMilli(next.getEnd().toEpochMilli())));

                    } else {
                        deque.add(next);
                    }
                },
                (dequeA, dequeB) -> {
                    throw new IllegalStateException("Must be accumulated serially, in order");
                },
                deque -> deque
        );

        return collector;
    }
}
