# On-device speaker turns

The screenshot shows names, timestamps and text. It does not identify the vendor's implementation. A reproducible pipeline is local speech segmentation, per-speaker embeddings and clustering, followed by an overlap-based join with timestamped ASR. The current studio saves plain text, so it needs timed ASR before it can attach names or labels to individual words. Let users name the anonymous clusters within each recording.

## Reproduce locally

Use Python 3.11 with sherpa-onnx 2.0.2, soundfile 0.14.0 and numpy 2.4.6. Download the segmentation archive and Chinese four-speaker test WAV from the [upstream release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-segmentation-models). Extract the INT8 pyannote 3.0 ONNX model. Obtain the 3D-Speaker Chinese ERes2Net base model from the [embedding release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models).

```sh
python diarize.py 0-four-speakers-zh.wav \
  --segmentation sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx \
  --embedding 3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx
```

On the upstream 56.86-second Chinese sample, local CPU inference on macOS returned 10 intervals and **7 clusters for 4 reference speakers** using automatic count and threshold 0.5. This single smoke test demonstrates timeline extraction and over-segmentation. It does not measure diarization error rate. See the [Kotlin and Android examples](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/index.html) for the device runtime.

Before offering this in a paid APK, compare labeled English, Mandarin, Japanese and overlapping meeting recordings on physical phones, including automatic count, optional known count, memory and runtime. Review the terms of both model artifacts before redistributing either. Use local aliases and keep the audio and speaker embeddings on device.
