package org.dcstacviewsrsrecorder.srs;

import reactor.netty.Connection;

public class RadioStationTest {

    //@Test
    public void test() throws InterruptedException {
        RadioStation rs = new RadioStation();
        Connection block = rs.play(
                "https://file-examples-com.github.io/uploads/2017/11/file_example_MP3_1MG.mp3",
                "13.236.5.24",
                5002,
                305000000
        ).block();
        block.onDispose().block();
    }
}
