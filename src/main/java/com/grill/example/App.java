package com.grill.example;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecHWConfig;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.LogCallback;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.tinylog.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

import static org.bytedeco.ffmpeg.avcodec.AVCodecContext.FF_THREAD_SLICE;
import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

public class App extends Application {

    /**** decoder variables ****/

    private AVHWContextInfo hardwareContext;

    private AVCodec decoder;
    private AVCodecContext m_VideoDecoderCtx;

    private AVCodecContext.Get_format_AVCodecContext_IntPointer formatCallback;

    private final int streamResolutionX = 1920;
    private final int streamResolutionY = 1080;

    // AV_HWDEVICE_TYPE_CUDA // example works with cuda
    // AV_HWDEVICE_TYPE_DXVA2 // producing Invalid data found on keyframe
    // AV_HWDEVICE_TYPE_D3D11VA // producing Invalid data found on keyframe
    private static final int HW_DEVICE_TYPE = AV_HWDEVICE_TYPE_DXVA2;

    private static final boolean USE_HW_ACCEL = true;

    private static final boolean USE_AV_EF_EXPLODE = true;

    public static void main(final String[] args) {
        //System.setProperty("prism.order", "d3d,sw");
        System.setProperty("prism.vsync", "false");
        Application.launch(App.class);
    }

    @Override
    public void start(final Stage primaryStage) {
        final Pane dummyPane = new Pane();
        dummyPane.setStyle("-fx-background-color: black");
        final Scene scene = new Scene(dummyPane, this.streamResolutionX, this.streamResolutionY);
        primaryStage.setScene(scene);
        primaryStage.show();
        primaryStage.setMinWidth(480);
        primaryStage.setMinHeight(360);

        this.initializeFFmpeg(result -> {
            if (!result) {
                Logger.error("FFmpeg could not be initialized correctly, terminating program");
                System.exit(1);
                return;
            }
            scene.setRoot(new StackPane());
            this.performTestFramesFeeding();
        });
    }

    private void initializeFFmpeg(final Consumer<Boolean> finishHandler) {
        FFmpegLogCallback.setLevel(AV_LOG_DEBUG); // Increase log level until the first frame is decoded
        FFmpegLogCallback.set();
        Pointer pointer = new Pointer((Pointer) null);
        AVCodec c;
        while ((c = av_codec_iterate(pointer)) != null) {
            if (av_codec_is_decoder(c) > 0)
                Logger.debug("{}:{} ", c.name().getString(), c.type());
        }

        this.decoder = avcodec_find_decoder(AV_CODEC_ID_H264); // usually decoder name is h264 and without hardware support it's yuv420p otherwise nv12
        if (this.decoder == null) {
            Logger.error("Unable to find decoder for format {}", "h264");
            finishHandler.accept(false);
            return;
        }
        Logger.info("Current decoder name: {}, {}", this.decoder.name().getString(), this.decoder.long_name().getString());

        if (true) {
            for (; ; ) {
                this.m_VideoDecoderCtx = avcodec_alloc_context3(this.decoder);
                if (this.m_VideoDecoderCtx == null) {
                    Logger.error("Unable to find decoder for format AV_CODEC_ID_H264");
                    if (this.hardwareContext != null) {
                        this.hardwareContext.free();
                        this.hardwareContext = null;
                    }
                    continue;
                }

                if (App.USE_HW_ACCEL) {
                    this.hardwareContext = this.createHardwareContext();
                    if (this.hardwareContext != null) {
                        Logger.info("Set hwaccel support");
                        this.m_VideoDecoderCtx.hw_device_ctx(this.hardwareContext.hwContext()); // comment to disable hwaccel
                    }
                } else {
                    Logger.info("Hwaccel manually disabled");
                }

                // Always request low delay decoding
                this.m_VideoDecoderCtx.flags(this.m_VideoDecoderCtx.flags() | AV_CODEC_FLAG_LOW_DELAY);

                // Allow display of corrupt frames and frames missing references
                this.m_VideoDecoderCtx.flags(this.m_VideoDecoderCtx.flags() | AV_CODEC_FLAG_OUTPUT_CORRUPT);
                this.m_VideoDecoderCtx.flags2(this.m_VideoDecoderCtx.flags2() | AV_CODEC_FLAG2_SHOW_ALL);

                if (App.USE_AV_EF_EXPLODE) {
                    // Report decoding errors to allow us to request a key frame
                    this.m_VideoDecoderCtx.err_recognition(this.m_VideoDecoderCtx.err_recognition() | AV_EF_EXPLODE);
                }

                // Enable slice multi-threading for software decoding
                if (this.m_VideoDecoderCtx.hw_device_ctx() == null) { // if not hw accelerated
                    this.m_VideoDecoderCtx.thread_type(this.m_VideoDecoderCtx.thread_type() | FF_THREAD_SLICE);
                    this.m_VideoDecoderCtx.thread_count(2/*AppUtil.getCpuCount()*/);
                } else {
                    // No threading for HW decode
                    this.m_VideoDecoderCtx.thread_count(1);
                }

                this.m_VideoDecoderCtx.width(this.streamResolutionX);
                this.m_VideoDecoderCtx.height(this.streamResolutionY);
                this.m_VideoDecoderCtx.pix_fmt(this.getDefaultPixelFormat());

                this.formatCallback = new AVCodecContext.Get_format_AVCodecContext_IntPointer() {
                    @Override
                    public int call(final AVCodecContext context, final IntPointer pixelFormats) {
                        final boolean hwDecodingSupported = context.hw_device_ctx() != null && App.this.hardwareContext != null;
                        final int preferredPixelFormat = hwDecodingSupported ?
                                App.this.hardwareContext.hwConfig().pix_fmt() :
                                context.pix_fmt();
                        int i = 0;
                        while (true) {
                            final int currentSupportedFormat = pixelFormats.get(i++);
                            System.out.println("Supported pixel formats " + currentSupportedFormat);
                            if (currentSupportedFormat == AV_PIX_FMT_NONE) {
                                break;
                            }
                        }

                        i = 0;
                        while (true) {
                            final int currentSupportedFormat = pixelFormats.get(i++);
                            if (currentSupportedFormat == preferredPixelFormat) {
                                Logger.info("[FFmpeg]: pixel format in format callback is {}", currentSupportedFormat);
                                return currentSupportedFormat;
                            }
                            if (currentSupportedFormat == AV_PIX_FMT_NONE) {
                                break;
                            }
                        }

                        i = 0;
                        while (true) { // try again and search for yuv
                            final int currentSupportedFormat = pixelFormats.get(i++);
                            if (currentSupportedFormat == AV_PIX_FMT_YUV420P) {
                                Logger.info("[FFmpeg]: Not found in first match so use {}", AV_PIX_FMT_YUV420P);
                                return currentSupportedFormat;
                            }
                            if (currentSupportedFormat == AV_PIX_FMT_NONE) {
                                break;
                            }
                        }

                        i = 0;
                        while (true) { // try again and search for nv12
                            final int currentSupportedFormat = pixelFormats.get(i++);
                            if (currentSupportedFormat == AV_PIX_FMT_NV12) {
                                Logger.info("[FFmpeg]: Not found in second match so use {}", AV_PIX_FMT_NV12);
                                return currentSupportedFormat;
                            }
                            if (currentSupportedFormat == AV_PIX_FMT_NONE) {
                                break;
                            }
                        }

                        Logger.info("[FFmpeg]: pixel format in format callback is using fallback {}", AV_PIX_FMT_NONE);
                        return AV_PIX_FMT_NONE;
                    }
                };
                this.m_VideoDecoderCtx.get_format(this.formatCallback);

                final AVDictionary options = new AVDictionary(null);
                final int result = avcodec_open2(this.m_VideoDecoderCtx, this.decoder, options);
                if (result < 0) {
                    Logger.error("avcodec_open2 was not successful");
                    finishHandler.accept(false);
                    return;
                }
                av_dict_free(options);
                break;
            }
        }

        if (this.decoder == null || this.m_VideoDecoderCtx == null) {
            finishHandler.accept(false);
            return;
        }
        finishHandler.accept(true);
    }

    private AVHWContextInfo createHardwareContext() {
        AVHWContextInfo result = null;
        for (int i = 0; ; i++) {
            final AVCodecHWConfig config = avcodec_get_hw_config(this.decoder, i);
            if (config == null) {
                break;
            }

            if ((config.methods() & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) < 0) {
                continue;
            }
            final int device_type = config.device_type();
            if (device_type != App.HW_DEVICE_TYPE) {
                continue;
            }
            final AVBufferRef hw_context = av_hwdevice_ctx_alloc(device_type);
            if (hw_context == null || av_hwdevice_ctx_create(hw_context, device_type, (String) null, null, 0) < 0) {
                Logger.error("HW accel not supported for type {}", device_type);
                av_free(config);
                av_free(hw_context);
            } else {
                Logger.info("HW accel created for type {}", device_type);
                result = new AVHWContextInfo(config, hw_context);
            }
            break;
        }

        return result;
    }

    @Override
    public void stop() {
        this.releaseNativeResources();
    }

    /*****************************/
    /*** test frame processing ***/
    /*****************************/
    
    private void performTestFramesFeeding() {
        final AVPacket pkt = av_packet_alloc();
        if (pkt == null) {
            return;
        }
        try (final BytePointer bp = new BytePointer(65_535 * 15)) {


            for (int i = 0; i < 1; i++) {
                final byte[] frameData = AVTestFrames.h264KeyTestFrame;

                bp.position(0);

                bp.put(frameData);
                bp.limit(frameData.length);

                pkt.data(bp);
                pkt.capacity(bp.capacity());
                pkt.size(frameData.length);
                pkt.position(0);
                pkt.limit(frameData.length);
                //pkt.flags(AV_PKT_FLAG_KEY);
                final AVFrame avFrame = av_frame_alloc();
                System.out.println("frameData.length " + frameData.length);

                final int err = avcodec_send_packet(this.m_VideoDecoderCtx, pkt); //fill_scaling_lists
                if (err < 0) {
                    final BytePointer buffer = new BytePointer(512);
                    av_strerror(err, buffer, buffer.capacity());
                    final String string = buffer.getString();
                    System.out.println("Error on decoding test frame " + err + " message " + string);
                    av_frame_free(avFrame);
                    return;
                }

                final int result = avcodec_receive_frame(this.m_VideoDecoderCtx, avFrame);
                final AVFrame decodedFrame;
                if (result == 0) {
                    if (this.m_VideoDecoderCtx.hw_device_ctx() == null) {
                        decodedFrame = avFrame;
                        System.out.println("SUCESS with SW decoding");
                    } else {
                        final AVFrame hwAvFrame = av_frame_alloc();
                        if (av_hwframe_transfer_data(hwAvFrame, avFrame, 0) < 0) {
                            System.out.println("Failed to transfer frame from hardware");
                            av_frame_unref(hwAvFrame);
                            decodedFrame = avFrame;
                        } else {
                            av_frame_unref(avFrame);
                            decodedFrame = hwAvFrame;
                            System.out.println("SUCESS with HW decoding");
                        }
                    }

                    av_frame_unref(decodedFrame);
                } else {
                    final BytePointer buffer = new BytePointer(512);
                    av_strerror(result, buffer, buffer.capacity());
                    final String string = buffer.getString();
                    System.out.println("error " + result + " message " + string);
                    av_frame_free(avFrame);
                }
            }
        } finally {
            if (pkt.stream_index() != -1) {
                av_packet_unref(pkt);
            }
            pkt.releaseReference();
        }
    }

    final Object releaseLock = new Object();
    private volatile boolean released = false;

    private void releaseNativeResources() {
        if (this.released) {
            return;
        }
        this.released = true;
        synchronized (this.releaseLock) {
            // Close the video codec
            if (this.m_VideoDecoderCtx != null) {
                avcodec_free_context(this.m_VideoDecoderCtx);
                this.m_VideoDecoderCtx = null;
            }

            // close the format callback
            if (this.formatCallback != null) {
                this.formatCallback.close();
                this.formatCallback = null;
            }

            // close hw context
            if (this.hardwareContext != null) {
                this.hardwareContext.free();
            }
        }
    }

    private int getDefaultPixelFormat() {
        return AV_PIX_FMT_YUV420P; // Always return yuv420p here
    }


    /*********************/
    /*** inner classes ***/
    /*********************/

    public static final class HexUtil {

        private HexUtil() {
        }

        public static byte[] unhexlify(final String argbuf) {
            final int arglen = argbuf.length();
            if (arglen % 2 != 0) {
                throw new RuntimeException("Odd-length string");
            } else {
                final byte[] retbuf = new byte[arglen / 2];

                for (int i = 0; i < arglen; i += 2) {
                    final int top = Character.digit(argbuf.charAt(i), 16);
                    final int bot = Character.digit(argbuf.charAt(i + 1), 16);
                    if (top == -1 || bot == -1) {
                        throw new RuntimeException("Non-hexadecimal digit found");
                    }

                    retbuf[i / 2] = (byte) ((top << 4) + bot);
                }

                return retbuf;
            }
        }
    }

    public static final class AVHWContextInfo {
        private final AVCodecHWConfig hwConfig;
        private final AVBufferRef hwContext;

        private volatile boolean freed = false;

        public AVHWContextInfo(final AVCodecHWConfig hwConfig, final AVBufferRef hwContext) {
            this.hwConfig = hwConfig;
            this.hwContext = hwContext;
        }

        public AVCodecHWConfig hwConfig() {
            return this.hwConfig;
        }

        public AVBufferRef hwContext() {
            return this.hwContext;
        }

        public void free() {
            if (this.freed) {
                return;
            }
            this.freed = true;
            av_free(this.hwConfig);
            av_free(this.hwContext);
        }


        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AVHWContextInfo that = (AVHWContextInfo) o;
            return freed == that.freed && Objects.equals(hwConfig, that.hwConfig) && Objects.equals(hwContext, that.hwContext);
        }

        @Override
        public int hashCode() {
            return Objects.hash(hwConfig, hwContext, freed);
        }

        @Override
        public String toString() {
            return "AVHWContextInfo[" +
                    "hwConfig=" + this.hwConfig + ", " +
                    "hwContext=" + this.hwContext + ']';
        }
    }

    public static final class AVTestFrames {

        private AVTestFrames() {

        }

        static {
            InputStream inputStream = null;
            try {
                inputStream = AVTestFrames.class.getClassLoader().getResourceAsStream("h264_test_key_frame.txt");
                final byte[] h264TestFrameBuffer = inputStream == null ? new byte[0] : inputStream.readAllBytes();
                final String h264TestFrame = new String(h264TestFrameBuffer, StandardCharsets.UTF_8);
                AVTestFrames.h264KeyTestFrame = HexUtil.unhexlify(h264TestFrame);
            } catch (final IOException e) {
                Logger.error(e, "Could not parse test frame");
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (final IOException e) {
                        Logger.error(e, "Could not close test frame input stream");
                    }
                }
            }
        }

        public static byte[] h264KeyTestFrame;
    }

    public static class FFmpegLogCallback extends LogCallback {

        private static final org.bytedeco.javacpp.tools.Logger logger = org.bytedeco.javacpp.tools.Logger.create(FFmpegLogCallback.class);

        static final FFmpegLogCallback instance = new FFmpegLogCallback().retainReference();

        public static FFmpegLogCallback getInstance() {
            return instance;
        }

        /**
         * Calls {@code avutil.setLogCallback(getInstance())}.
         */
        public static void set() {
            setLogCallback(getInstance());
        }

        /**
         * Returns {@code av_log_get_level()}.
         **/
        public static int getLevel() {
            return av_log_get_level();
        }

        /**
         * Calls {@code av_log_set_level(level)}.
         **/
        public static void setLevel(int level) {
            av_log_set_level(level);
        }

        @Override
        public void call(int level, BytePointer msg) {
            switch (level) {
                case AV_LOG_PANIC, AV_LOG_FATAL, AV_LOG_ERROR -> logger.error(msg.getString());
                case AV_LOG_WARNING -> logger.warn(msg.getString());
                case AV_LOG_INFO -> logger.info(msg.getString());
                case AV_LOG_VERBOSE, AV_LOG_DEBUG, AV_LOG_TRACE -> logger.debug(msg.getString());
                default -> {
                    assert false;
                }
            }
        }
    }
}
