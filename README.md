# dcs-tacview-srs-recorder

Tacview has a neat feature that can synchronise media with playback of the trackfile, however it can be awkward
to align the timing of the media recording with the trackfile.

This webservice connects to a realtime tacview feed as well as an SRS server and records the timestamped tacview and 
audio data to a local datastore. It can then serve a tacview acmi file and audio file cropped to the same time interval 
so the audio is already aligned with the trackfile when imported into tacview.

There is also an SRS broadcast function built on LavaPlayer. LavaPlayer is used by a lot of discord bots for playing 
media from disparate sources, but also works well for SRS since it uses the same Opus encoding.  

This service is functional, but could do with some more user interface work. Also I haven't figured out how to pad the
start of an acmi file yet if data is not available. 

https://tacview.fandom.com/wiki/Synchronized_Audio/Video_Playback
https://www.tacview.net/documentation/realtime/en/

https://www.digitalcombatsimulator.com/en/
https://www.tacview.net/
http://dcssimpleradio.com/
https://github.com/sedmelluq/lavaplayer

Shameless plug for my AWS DCS service for staging a short lived DCS server with realtime Tacview and SRS services configured.
https://ready-room.net/

## Development

Java spring boot project built with apache maven.

```
mvn spring-boot:run
```
Navigate to http://localhost:5000

### Build

```
mvn clean install
```

### Hosting - AWS Elastic beanstalk

The service can be hosted on AWS Elastic beanstalk using the managed java platform. 
It's inexpensive, especially if requesting a spot priced instance. Just need to upload a zip
containing the built target/*.jar file as well as the .platform dir. AWS will do the rest.

https://stackoverflow.com/questions/18908426/increasing-client-max-body-size-in-nginx-conf-on-aws-elastic-beanstalk