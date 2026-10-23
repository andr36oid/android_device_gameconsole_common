package org.andr36oid.usbmode;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.preference.PreferenceManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class Soundboard {
    static final String[] FILES = {"airhorn","rimshot","sad-trombone","crickets","drumroll","applause",
            "victory","buzzer","laser","coin","power-up","level-complete"};
    final SoundMixer mixer = new SoundMixer();
    final String[] labels;
    private final short[][] mSounds = new short[12][];
    private final Context mContext;
    private int mPrevious, mVolume = 75;
    private boolean mArmed, mChoose;
    synchronized boolean chooseRequested() { boolean value = mChoose; mChoose = false; return value; }
    synchronized void rearm() { mArmed = false; mPrevious = 0; }
    synchronized void reset() { microphone(false); mArmed = false; mPrevious = 0; mixer.stop(); }
    private volatile boolean mClosed;
    private android.media.AudioRecord mMicrophone;
    boolean hasMicrophone() {
        android.media.AudioManager manager = mContext.getSystemService(android.media.AudioManager.class);
        for (android.media.AudioDeviceInfo device : manager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)) {
            int type = device.getType();
            if (type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC || type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET || type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE) return true;
        }
        return false;
    }
    synchronized void microphone(boolean enable) {
        if (mMicrophone != null) { mMicrophone.stop(); mMicrophone.release(); mMicrophone = null; }
        if (!enable || mClosed) return;
        int size = android.media.AudioRecord.getMinBufferSize(48000,android.media.AudioFormat.CHANNEL_IN_MONO,android.media.AudioFormat.ENCODING_PCM_16BIT);
        if (size <= 0) return;
        android.media.AudioRecord mic = null;
        try {
            mic = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC,48000,
                    android.media.AudioFormat.CHANNEL_IN_MONO,android.media.AudioFormat.ENCODING_PCM_16BIT,Math.max(size,4096));
            if (mic.getState() != android.media.AudioRecord.STATE_INITIALIZED) { mic.release(); return; }
            mic.startRecording(); mMicrophone = mic;
        } catch (RuntimeException e) { if (mic != null) mic.release(); }
    }
    synchronized boolean microphone() { return mMicrophone != null; }
    synchronized short[] microphone(int frames) {
        if (mMicrophone == null) return null;
        short[] mono = new short[frames], stereo = new short[frames * 2];
        int count = mMicrophone.read(mono,0,frames,android.media.AudioRecord.READ_NON_BLOCKING);
        for (int i = 0; i < Math.max(0,count); i++) stereo[2 * i] = stereo[2 * i + 1] = mono[i];
        return stereo;
    }
    Soundboard(Context context) {
        mContext = context; labels = context.getString(R.string.usb_sound_examples).split("\\|");
        if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean("usb_sound_live",false)
                && context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED && hasMicrophone()) microphone(true);
        mVolume = PreferenceManager.getDefaultSharedPreferences(context).getInt("sound_volume",75); mixer.volume(mVolume);
        new Thread(() -> {
            for (int i = 0; i < 12 && !mClosed; i++) {
                String selected = PreferenceManager.getDefaultSharedPreferences(context).getString("sound_" + i,"");
                try { load(i,selected); } catch (IOException | RuntimeException e) {
                    try { load(i,""); } catch (IOException | RuntimeException ignored) { }
                }
            }
        },"USB sound pads").start();
    }
    synchronized void update(byte[] frame) {
        if (mClosed) return;
        int buttons = (frame[1] & 255) | ((frame[2] & 255) << 8) | ((frame[3] & 255) << 16);
        if (frame[13] == 0 || frame[14] != 0) { mixer.stop(); mArmed = false; return; }
        if (!mArmed) { mArmed = buttons == 0; mPrevious = buttons; return; }
        int down = buttons & ~mPrevious; mPrevious = buttons;
        if ((buttons & (1 << 5)) != 0) {
            if ((down & (1 << 11)) != 0) mixer.stop();
            if ((down & (1 << 14)) != 0) mChoose = true;
            if ((down & 3) != 0) volume(mVolume + ((down & 1) != 0 ? 5 : -5));
            return;
        }
        for (int i = 0; i < 12; i++) if ((down & (1 << MacroProfiles.BUTTONS[i])) != 0 && mSounds[i] != null) mixer.play(mSounds[i]);
    }
    synchronized void volume(int volume) {
        mVolume = Math.max(0,Math.min(100,volume)); mixer.volume(mVolume);
        PreferenceManager.getDefaultSharedPreferences(mContext).edit().putInt("sound_volume",mVolume).apply();
    }
    synchronized void close() { microphone(false); mClosed = true; mixer.stop(); }
    String[] choices() throws IOException {
        String text = TextFiles.request("S\n");
        return text.isEmpty() ? new String[0] : text.substring(1).split("\n");
    }
    void load(int pad, String name) throws IOException {
        File file = File.createTempFile("sound-",".audio",mContext.getCacheDir());
        try {
            try (FileOutputStream out = new FileOutputStream(file)) {
                if (name.isEmpty()) {
                    try (InputStream in = mContext.getAssets().open("soundboard/" + FILES[pad] + ".wav")) {
                        byte[] bytes = new byte[8192]; int count; while ((count = in.read(bytes)) > 0) out.write(bytes,0,count);
                    }
                } else {
                    int offset = 0;
                    while (true) {
                        byte[] bytes = TextFiles.bytes("G\n" + name + "\n" + offset);
                        if (bytes.length == 0) break;
                        offset += bytes.length; if (offset > 8 * 1024 * 1024) throw new IOException("Sound size"); out.write(bytes);
                    }
                }
            }
            short[] pcm = decode(file);
            synchronized (this) {
                if (mClosed) return;
                mSounds[pad] = pcm;
                labels[pad] = name.isEmpty() ? mContext.getString(R.string.usb_sound_examples).split("\\|")[pad] : name;
                PreferenceManager.getDefaultSharedPreferences(mContext).edit().putString("sound_" + pad,name).apply();
            }
        } finally { file.delete(); }
    }
    private short[] decode(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor(); MediaCodec decoder = null;
        try {
            extractor.setDataSource(file.getPath()); int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/")) { track = i; break; }
            if (track < 0) throw new IOException("Audio track");
            extractor.selectTrack(track); MediaFormat format = extractor.getTrackFormat(track);
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if (channels < 1 || channels > 2 || rate < 8000 || rate > 192000) throw new IOException("Audio format");
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)); decoder.configure(format,null,null,0); decoder.start();
            ByteArrayOutputStream pcm = new ByteArrayOutputStream(); MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEnd = false, done = false; long deadline = android.os.SystemClock.uptimeMillis() + 10000;
            while (!done && !mClosed && android.os.SystemClock.uptimeMillis() < deadline) {
                if (!inputEnd) {
                    int input = decoder.dequeueInputBuffer(1000);
                    if (input >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(input); int size = extractor.readSampleData(buffer,0);
                        inputEnd = size < 0; decoder.queueInputBuffer(input,0,Math.max(0,size),inputEnd ? 0 : extractor.getSampleTime(),inputEnd ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        if (!inputEnd) extractor.advance();
                    }
                }
                int output = decoder.dequeueOutputBuffer(info,1000);
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    format = decoder.getOutputFormat(); rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (channels < 1 || channels > 2 || rate < 8000 || rate > 192000) throw new IOException("Audio format");
                    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING) && format.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT) throw new IOException("PCM encoding");
                }
                if (output >= 0) {
                    ByteBuffer buffer = decoder.getOutputBuffer(output); buffer.position(info.offset); buffer.limit(info.offset + info.size);
                    byte[] bytes = new byte[info.size]; buffer.get(bytes); pcm.write(bytes);
                    done = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0; decoder.releaseOutputBuffer(output,false);
                    if (pcm.size() > rate * channels * 2 * 10) throw new IOException("Sound duration");
                }
            }
            if (!done || channels < 1 || channels > 2 || rate <= 0) throw new IOException("Audio decoding");
            ByteBuffer bytes = ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
            short[] source = new short[bytes.remaining() / 2]; bytes.asShortBuffer().get(source);
            int frames = source.length / channels, length = (int) ((long) frames * 48000 / rate);
            short[] result = new short[length * 2];
            for (int i = 0; i < length; i++) for (int ch = 0; ch < 2; ch++) {
                double position = i * (double) rate / 48000; int a = Math.min(frames - 1,(int) position), b = Math.min(frames - 1,a + 1);
                double fraction = position - a; int c = Math.min(ch,channels - 1);
                result[i * 2 + ch] = (short) Math.round(source[a * channels + c] * (1 - fraction) + source[b * channels + c] * fraction);
            }
            return result;
        } finally { if (decoder != null) { try { decoder.stop(); } catch (IllegalStateException ignored) { } decoder.release(); } extractor.release(); }
    }
}
