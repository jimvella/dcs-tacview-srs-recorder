package org.dcstacviewsrsrecorder.recordingservice.web;

import java.time.*;
import java.time.format.DateTimeFormatter;

/*
    10 figure group format
    The format is yymmddhhmm. For example: 1215 hours UTC on 23 March 2010 would be written as 1003231215
    https://vfrg.casa.gov.au/pre-flight-planning/preparation/time/
 */
public class AviationDateTimeFormat {

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyMMddHHmm");

    public static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, ZoneId.of("UTC")).format(formatter);
    }

    public static Instant parse(String instant) {
        return LocalDateTime.parse(instant, formatter).toInstant(ZoneOffset.UTC);
    }
}
