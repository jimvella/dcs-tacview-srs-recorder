package org.dcstacviewsrsrecorder.recordingservice.web;

import org.dcstacviewsrsrecorder.srs.RadioStation;
import org.dcstacviewsrsrecorder.srs.ShortGuid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.view.RedirectView;
import reactor.netty.Connection;
import reactor.netty.DisposableChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class BroadcastController {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();

    @GetMapping("/broadcast")
    public String index() {
        return "upload";
    }

    @PostMapping("/broadcast/upload")
    public RedirectView upload(
            @RequestParam String host,
            @RequestParam String port,
            @RequestParam String frequency,
            @RequestParam String url,
            @RequestParam Optional<MultipartFile> multipartFile
    ) {
        Optional<Path> tempFile = multipartFile.map(file -> {
            logger.info("Uploaded - Host: " + host + ", Port: " + port + ", Frequency: " + frequency + ", File: " + file.getOriginalFilename());
            try {
                Path temp = Files.createTempFile("", "");
                file.transferTo(temp);

                return temp;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        RadioStation rs = new RadioStation();
        String guid = ShortGuid.encode(UUID.randomUUID().toString());
        Connection connection = rs.play(
                tempFile.isPresent() ? tempFile.get().toFile().getAbsolutePath() : url,
                host,
                Integer.parseInt(port),
                Double.parseDouble(frequency)
        ).block();
        connection.onDispose(() -> {
            connections.remove(guid);
            tempFile.ifPresent(f -> {
                try {
                    Files.delete(f);
                } catch (IOException e) {
                    logger.warn("Failed to delete temp file: " + f);
                }
            });
        });
        connections.put(guid, connection);

        RedirectView redirectView = new RedirectView("/broadcast/" + guid + "/cancel", true);
        redirectView.setStatusCode(HttpStatus.SEE_OTHER);
        return redirectView;
    }

    @GetMapping("/broadcast/{guid}/cancel")
    public String cancelView(@PathVariable String guid) {
        return "cancel";
    }

    @PostMapping("/broadcast/{guid}/cancel")
    public String cancel(@PathVariable String guid) {
        logger.info("Canceling " + guid);
        Optional.ofNullable(connections.get(guid)).ifPresent(DisposableChannel::dispose);
        return "redirect:/broadcast";
    }
}
