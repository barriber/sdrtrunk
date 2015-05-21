# JMBE Library #

The Java Multi-Band Excitation (JMBE) library provides decoder support for
converting IMBE encoded audio frames to normal PCM audio that you can hear over
your computer speakers.

Website: [JMBE](https://github.com/DSheirer/jmbe)

## Patent Warning ##

The JMBE library contains source code that may be patent protected.  Users
should check local laws before downloading, compiling, using, or DISTRIBUTING
compiled versions of the JMBE library.

## Copyright Notice ##

Copyrights for the terms MBE and IMBE are the property of their respective
owners.

## Adding JMBE to SDRTrunk for decoding MBE audio ##

# Follow the instructions contained in the README file on the JMBE website for downloading the source code and using Apache Ant to compile the source.
# Copy the compiled library (jmbe-x.x.x.jar) file to the same folder where your SDRTrunk application is located.  The correct folder should contain the SDRTrunk.jar file and the windows and linux application run scripts.
# Start SDRTrunk normally

## Troubleshooting ##

If you download, compile and copy the JMBE library jar file to the same directory
as the SDRTrunk application, you should not have any issues.

SDRTrunk will generate a log entry to let you know if it discovered the jmbe
library correctly.

> 09:07:03.972 INFO  gui.SDRTrunk - Available Audio Converter: class jmbe.audio.imbe.IMBEAudioConverter

Each P25 decoding channel that you enable will also log availability of the
library

> 09:07:05.376 INFO d.p.audio.P25AudioOutput - IMBE audio converter library loaded successfully

Or, when the library cannot be found, each channel will log:

> 09:13:45.941 INFO  d.p.audio.P25AudioOutput - could NOT find/load IMBE audio converter library