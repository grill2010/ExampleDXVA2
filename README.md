# ExampleDXVA2
This project uses ffmpeg 6.0 internally via javacv. There is just one [patch](https://github.com/bytedeco/javacpp-presets/commit/63acf680ef0d95cbdda1b3840450e4333a78bde0#diff-8824bdfae6ac233bb3ae63d4cabbb078313c3296a4fcc9f8612ed858b158aa5fR123) in place in the native lib which is relevant to reproduce the issue.

The example can be build and started via gradle. To build it execute *.\gradlew build* and to run it just execute *.\gradlew run*. Java 17 must be installed on your machine in order to run the project.

The DXVA2 decoder will not be able to decode the h264 key frame because of a too small buffer returned by [IDirectXVideoDecoder_GetBuffer](https://github.com/FFmpeg/FFmpeg/blob/9d70e74d255dbe37af52b0efffc0f93fd7cb6103/libavcodec/dxva2.c#L817).
