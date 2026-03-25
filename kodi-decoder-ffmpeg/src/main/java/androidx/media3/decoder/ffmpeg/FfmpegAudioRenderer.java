/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.decoder.ffmpeg;

import static androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY;
import static androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING;
import static androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_UNSUPPORTED;
import static com.google.common.base.Preconditions.checkNotNull;

import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioSink.SinkFormatSupport;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

/** Decodes and renders audio using FFmpeg. */
@UnstableApi
public final class FfmpegAudioRenderer extends DecoderAudioRenderer<FfmpegAudioDecoder> {

  private static final String TAG = "FfmpegAudioRenderer";

  /** The number of input and output buffers. */
  private static final int NUM_BUFFERS = 16;

  /** The default input buffer size. */
  private static final int DEFAULT_INPUT_BUFFER_SIZE = 960 * 6;

  private volatile int userCenterMixLevelDb;
  @Nullable private volatile String requestedOutputLayoutName;
  private volatile int requestedOutputChannelCount;
  @Nullable private volatile FfmpegAudioDecoder activeDecoder;
  private volatile boolean rendererEnabled;
  private volatile boolean downmixActive;

  public FfmpegAudioRenderer() {
    this(/* eventHandler= */ null, /* eventListener= */ null);
  }

  /**
   * Creates a new instance.
   *
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param audioProcessors Optional {@link AudioProcessor}s that will process audio before output.
   */
  public FfmpegAudioRenderer(
      @Nullable Handler eventHandler,
      @Nullable AudioRendererEventListener eventListener,
      AudioProcessor... audioProcessors) {
    this(
        eventHandler,
        eventListener,
        new DefaultAudioSink.Builder().setAudioProcessors(audioProcessors).build());
  }

  /**
   * Creates a new instance.
   *
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param audioSink The sink to which audio will be output.
   */
  public FfmpegAudioRenderer(
      @Nullable Handler eventHandler,
      @Nullable AudioRendererEventListener eventListener,
      AudioSink audioSink) {
    super(eventHandler, eventListener, audioSink);
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  protected void onEnabled(boolean joining, boolean mayRenderStartOfStream)
      throws ExoPlaybackException {
    super.onEnabled(joining, mayRenderStartOfStream);
    rendererEnabled = true;
  }

  @Override
  protected void onDisabled() {
    try {
      super.onDisabled();
    } finally {
      rendererEnabled = false;
      downmixActive = false;
      activeDecoder = null;
    }
  }

  @Override
  protected @C.FormatSupport int supportsFormatInternal(Format format) {
    String mimeType = checkNotNull(format.sampleMimeType);
    if (!FfmpegLibrary.isAvailable() || !MimeTypes.isAudio(mimeType)) {
      return C.FORMAT_UNSUPPORTED_TYPE;
    }
    if (!FfmpegLibrary.supportsFormat(mimeType)) {
      return C.FORMAT_UNSUPPORTED_SUBTYPE;
    }
    if (format.channelCount <= 0 || format.sampleRate <= 0) {
      return format.cryptoType == C.CRYPTO_TYPE_NONE
          ? C.FORMAT_HANDLED
          : C.FORMAT_UNSUPPORTED_DRM;
    }
    int outputChannelCount = resolveOutputChannelCount(format.channelCount);
    boolean supportsConfiguredOutput =
        sinkSupportsFormat(format, C.ENCODING_PCM_16BIT, outputChannelCount)
            || sinkSupportsFormat(format, C.ENCODING_PCM_FLOAT, outputChannelCount);
    if (!supportsConfiguredOutput) {
      return C.FORMAT_UNSUPPORTED_SUBTYPE;
    }
    if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
      return C.FORMAT_UNSUPPORTED_DRM;
    }
    return C.FORMAT_HANDLED;
  }

  @Override
  public @AdaptiveSupport int supportsMixedMimeTypeAdaptation() {
    return ADAPTIVE_NOT_SEAMLESS;
  }

  @Override
  protected FfmpegAudioDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
      throws FfmpegDecoderException {
    TraceUtil.beginSection("createFfmpegAudioDecoder");
    int initialInputBufferSize =
        format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
    int outputChannelCount = resolveOutputChannelCount(format.channelCount);
    @Nullable
    String outputLayoutName =
        outputChannelCount < format.channelCount ? requestedOutputLayoutName : null;
    downmixActive = outputChannelCount < format.channelCount;
    FfmpegAudioDecoder decoder =
        new FfmpegAudioDecoder(
            format,
            NUM_BUFFERS,
            NUM_BUFFERS,
            initialInputBufferSize,
            shouldOutputFloat(format, outputChannelCount),
            outputChannelCount,
            outputLayoutName);
    decoder.setUserCenterMixLevelDb(userCenterMixLevelDb);
    activeDecoder = decoder;
    TraceUtil.endSection();
    return decoder;
  }

  @Override
  protected Format getOutputFormat(FfmpegAudioDecoder decoder) {
    checkNotNull(decoder);
    return new Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setChannelCount(decoder.getChannelCount())
        .setSampleRate(decoder.getSampleRate())
        .setPcmEncoding(decoder.getEncoding())
        .build();
  }

  /** Sets the center-mix offset in dB relative to FFmpeg metadata or the Kodi default (-3 dB). */
  public void setCenterMixLevelDb(int centerMixLevelDb) {
    userCenterMixLevelDb = centerMixLevelDb;
    @Nullable FfmpegAudioDecoder decoder = activeDecoder;
    if (decoder != null) {
      decoder.setUserCenterMixLevelDb(centerMixLevelDb);
    }
  }

  /** Sets the desired FFmpeg output layout used for Kodi-style downmix decisions. */
  public void setAudioOutputChannels(@Nullable String outputLayoutName, int outputChannelCount) {
    requestedOutputLayoutName = outputLayoutName;
    requestedOutputChannelCount = outputChannelCount;
  }

  /** Returns whether this renderer is the active playback path for FFmpeg downmix + center mix. */
  public boolean isCenterMixActive() {
    return rendererEnabled && activeDecoder != null && downmixActive;
  }

  /**
   * Returns whether the renderer's {@link AudioSink} supports the PCM format that will be output
   * from the decoder for the given input format and requested output encoding.
   */
  private boolean sinkSupportsFormat(
      Format inputFormat, @C.PcmEncoding int pcmEncoding, int channelCount) {
    if (channelCount <= 0 || inputFormat.sampleRate <= 0) {
      return false;
    }
    return sinkSupportsFormat(Util.getPcmFormat(pcmEncoding, channelCount, inputFormat.sampleRate));
  }

  private boolean shouldOutputFloat(Format inputFormat, int outputChannelCount) {
    if (!sinkSupportsFormat(inputFormat, C.ENCODING_PCM_16BIT, outputChannelCount)) {
      // We have no choice because the sink doesn't support 16-bit integer PCM.
      return true;
    }

    @SinkFormatSupport
    int formatSupport =
        getSinkFormatSupport(
            Util.getPcmFormat(
                C.ENCODING_PCM_FLOAT, outputChannelCount, inputFormat.sampleRate));
    switch (formatSupport) {
      case SINK_FORMAT_SUPPORTED_DIRECTLY:
        // AC-3 is always 16-bit, so there's no point using floating point. Assume that it's worth
        // using for all other formats.
        return !MimeTypes.AUDIO_AC3.equals(inputFormat.sampleMimeType);
      case SINK_FORMAT_UNSUPPORTED:
      case SINK_FORMAT_SUPPORTED_WITH_TRANSCODING:
      default:
        // Always prefer 16-bit PCM if the sink does not provide direct support for floating point.
        return false;
    }
  }

  private int resolveOutputChannelCount(int inputChannelCount) {
    if (inputChannelCount <= 0) {
      return inputChannelCount;
    }
    int configuredChannelCount = requestedOutputChannelCount;
    if (configuredChannelCount <= 0 || configuredChannelCount >= inputChannelCount) {
      return inputChannelCount;
    }
    return configuredChannelCount;
  }
}
