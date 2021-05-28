package org.dcstacviewsrsrecorder.srs;

import org.junit.jupiter.api.Test;
import reactor.netty.Connection;

import java.util.LinkedList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

public class RadioReceiverTest {

    /*
        Test that we can send and receive audio from an SRS service.
     */
    //@Test
    public void test() throws InterruptedException {
        String host = "13.236.5.24";
        int port = 5002;
        double frequency = 305000000;

        List<UdpVoicePacket> list = new LinkedList<>();
        RadioReceiver radioReceiver = new RadioReceiver(List.of(frequency), list::add);
        Connection recevier = radioReceiver.connect(
                host,
                port
        ).block();

        RadioStation rs = new RadioStation();
        Connection block = rs.play(
                "https://file-examples-com.github.io/uploads/2017/11/file_example_MP3_1MG.mp3",
                host,
                port,
                frequency
        ).block();
        block.onDispose().block();
        recevier.dispose();

        assertThat("Audio packets were received", list.size(), greaterThanOrEqualTo(1000));
    }
}
