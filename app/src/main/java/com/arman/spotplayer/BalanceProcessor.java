package com.arman.spotplayer;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Per-channel PCM gain applied inside ExoPlayer's audio chain.
 *
 * ExoPlayer.setVolume() is mono, so stereo balance cannot live there.
 * This processor multiplies channel 0 by gainL and channel 1 by gainR
 * (extra channels get the average; mono gets the average too).
 * Anything that isn't 16-bit int or 32-bit float PCM passes through
 * untouched, so playback can never break because of this processor.
 */
public final class BalanceProcessor extends BaseAudioProcessor {

    private volatile float gainL = 1f;
    private volatile float gainR = 1f;
    private int encoding = C.ENCODING_INVALID;
    private int channelCount = 2;

    /** Live-updateable gains; both default to 1 (center). */
    public void setGains(float l, float r) {
        gainL = l;
        gainR = r;
    }

    @Override
    protected AudioProcessor.AudioFormat onConfigure(
            AudioProcessor.AudioFormat inputAudioFormat)
            throws AudioProcessor.UnhandledAudioFormatException {
        encoding = inputAudioFormat.encoding;
        channelCount = inputAudioFormat.channelCount;
        // Accept everything we can scale; anything else is passed through
        // by queueInput instead of throwing here (never break playback).
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int remaining = input.remaining();
        if (remaining == 0) return;
        float gl = gainL;
        float gr = gainR;
        boolean scalable = (encoding == C.ENCODING_PCM_16BIT
                || encoding == C.ENCODING_PCM_FLOAT)
                && !(gl == 1f && gr == 1f);
        ByteBuffer out = replaceOutputBuffer(remaining);
        if (!scalable) {
            out.put(input);
            out.flip();
            return;
        }
        input.order(ByteOrder.LITTLE_ENDIAN);
        out.order(ByteOrder.LITTLE_ENDIAN);
        int channels = Math.max(1, channelCount);
        if (encoding == C.ENCODING_PCM_16BIT) {
            int n = remaining / 2;
            for (int i = 0; i < n; i++) {
                int ch = i % channels;
                float g = ch == 0 ? gl : (ch == 1 ? gr : (gl + gr) / 2f);
                short s = input.getShort();
                int v = (int) (s * g);
                if (v > 32767) v = 32767;
                else if (v < -32768) v = -32768;
                out.putShort((short) v);
            }
            while (input.hasRemaining()) out.put(input.get());
        } else { // ENCODING_PCM_FLOAT
            int n = remaining / 4;
            for (int i = 0; i < n; i++) {
                int ch = i % channels;
                float g = ch == 0 ? gl : (ch == 1 ? gr : (gl + gr) / 2f);
                out.putFloat(input.getFloat() * g);
            }
            while (input.hasRemaining()) out.put(input.get());
        }
        out.flip();
    }
}
