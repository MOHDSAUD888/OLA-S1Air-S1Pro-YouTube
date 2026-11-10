package dev.olatool.mirror;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * On-dash screen agent. Run as root via app_process (not installed), so the
 * hidden SurfaceControl / InputManager calls below are allowed:
 *
 *   CLASSPATH=/data/local/tmp/mirror.apk app_process / \
 *       dev.olatool.mirror.Agent [port] [width] [height] [bitrate]
 *
 * It mirrors the built-in display into an H.264 encoder (exactly scrcpy's
 * Android-10 technique) and streams it over a plain TCP socket the phone
 * connects to directly over Wi-Fi. The same socket carries touch events back,
 * which are injected into the system.
 *
 * Wire protocol (all big-endian):
 *   server -> client: header  = int width, int height
 *                     packet  = int len, byte flags, len bytes of H.264
 *                               flags bit0 = codec config (SPS/PPS)
 *                               flags bit1 = key frame
 *   client -> server: control = byte action (0 down,1 up,2 move), int x, int y
 *
 * Everything hidden is called by reflection so the module compiles against the
 * public SDK with no @hide stubs.
 */
public final class Agent {

    private static final int COLOR_FORMAT_SURFACE = 0x7F000789; // COLOR_FormatSurface
    private static final int INJECT_ASYNC = 0;                  // INJECT_INPUT_EVENT_MODE_ASYNC
    private static final int SOURCE_TOUCHSCREEN = 0x00001002;

    private long downTime;

    public static void main(String[] args) throws Exception {
        int port = arg(args, 0, 8099);
        int width = arg(args, 1, 800);
        int height = arg(args, 2, 480);
        int bitrate = arg(args, 3, 6_000_000);
        new Agent().serve(port, width, height, bitrate);
    }

    private static int arg(String[] a, int i, int def) {
        try {
            return (i < a.length) ? Integer.parseInt(a[i]) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void serve(int port, int width, int height, int bitrate) throws Exception {
        // SO_REUSEADDR before bind: an immediate relaunch (after the app pkills
        // the old agent) would otherwise fail while the port is in TIME_WAIT.
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        log("listening on 0.0.0.0:" + port + " " + width + "x" + height);
        while (true) {
            Socket socket = server.accept();
            socket.setTcpNoDelay(true);
            log("client " + socket.getInetAddress());
            try {
                session(socket, width, height, bitrate);
            } catch (Exception e) {
                log("session ended: " + e);
            } finally {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void session(Socket socket, int width, int height, int bitrate) throws Exception {
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        DataInputStream in = new DataInputStream(socket.getInputStream());
        out.writeInt(width);
        out.writeInt(height);
        out.flush();

        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT_SURFACE);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
        // Keep emitting frames when the dash is static, else the decoder stalls.
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        Surface inputSurface = codec.createInputSurface();
        IBinder display = createMirrorDisplay(inputSurface, width, height);
        codec.start();

        // Control messages flow the other way on the same socket.
        Thread control = new Thread(() -> readControl(in), "control");
        control.setDaemon(true);
        control.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int index = codec.dequeueOutputBuffer(info, 100_000);
                if (index >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        byte[] data = new byte[info.size];
                        buffer.get(data);
                        int flags = 0;
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) flags |= 1;
                        if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) flags |= 2;
                        out.writeInt(data.length);
                        out.writeByte(flags);
                        out.write(data);
                        out.flush();
                    }
                    codec.releaseOutputBuffer(index, false);
                }
                if (socket.isClosed()) break;
            }
        } finally {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            codec.release();
            destroyDisplay(display);
            try {
                inputSurface.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void readControl(DataInputStream in) {
        try {
            while (true) {
                int action = in.readUnsignedByte();
                int x = in.readInt();
                int y = in.readInt();
                injectTouch(action, x, y);
            }
        } catch (EOFException eof) {
            // client gone
        } catch (Exception e) {
            log("control: " + e);
        }
    }

    private void injectTouch(int action, int x, int y) {
        long now = SystemClock.uptimeMillis();
        int motion;
        switch (action) {
            case 0: motion = MotionEvent.ACTION_DOWN; downTime = now; break;
            case 1: motion = MotionEvent.ACTION_UP; break;
            default: motion = MotionEvent.ACTION_MOVE; break;
        }
        MotionEvent ev = MotionEvent.obtain(downTime, now, motion, x, y, 0);
        ev.setSource(SOURCE_TOUCHSCREEN);
        try {
            Object im = Class.forName("android.hardware.input.InputManager")
                    .getMethod("getInstance").invoke(null);
            Method inject = im.getClass()
                    .getMethod("injectInputEvent", InputEvent.class, int.class);
            inject.invoke(im, ev, INJECT_ASYNC);
        } catch (Exception e) {
            log("inject: " + e);
        } finally {
            ev.recycle();
        }
    }

    // ------------------------------------------------- hidden SurfaceControl

    private IBinder createMirrorDisplay(Surface surface, int width, int height) throws Exception {
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        IBinder display = (IBinder) sc.getMethod("createDisplay", String.class, boolean.class)
                .invoke(null, "pegasus-mirror", false);
        sc.getMethod("openTransaction").invoke(null);
        try {
            sc.getMethod("setDisplaySurface", IBinder.class, Surface.class)
                    .invoke(null, display, surface);
            sc.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                    .invoke(null, display, 0, new Rect(0, 0, width, height), new Rect(0, 0, width, height));
            sc.getMethod("setDisplayLayerStack", IBinder.class, int.class)
                    .invoke(null, display, 0);
        } finally {
            sc.getMethod("closeTransaction").invoke(null);
        }
        return display;
    }

    private void destroyDisplay(IBinder display) {
        try {
            Class.forName("android.view.SurfaceControl")
                    .getMethod("destroyDisplay", IBinder.class).invoke(null, display);
        } catch (Exception ignored) {
        }
    }

    private static void log(String s) {
        System.err.println("[mirror] " + s);
    }
}
