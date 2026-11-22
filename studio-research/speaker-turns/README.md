# On-device speaker turns

The screenshot shows names, timestamps and text. It does not identify the vendor's implementation. A reproducible pipeline is local speech segmentation, per-speaker embeddings and clustering, followed by an overlap-based join with timestamped ASR. The Android preview can request timestamped ASR segments and match each one to the turn with the longest overlap. Equal overlaps across different speakers stay unattributed. A plain-text transcript still shows a separate speaker timeline. Users can name speakers within one recording.

## Reproduce locally

Use Python 3.11 with sherpa-onnx 2.0.2, soundfile 0.14.0 and numpy 2.4.6. Download the segmentation archive and Chinese four-speaker test WAV from the [upstream release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-segmentation-models). Extract the INT8 pyannote 3.0 ONNX model. Obtain the 3D-Speaker Chinese ERes2Net base model from the [embedding release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models).

```sh
python diarize.py 0-four-speakers-zh.wav \
  --segmentation sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx \
  --embedding 3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx
```

On the upstream 56.86-second Chinese sample, local CPU inference on macOS returned 10 intervals and **7 clusters for 4 reference speakers** using automatic count and threshold 0.5. This single smoke test demonstrates timeline extraction and over-segmentation. It does not measure diarization error rate. See the [Kotlin and Android examples](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/index.html) for the device runtime.

Before offering this in a paid APK, compare labeled English, Mandarin, Japanese and overlapping meeting recordings on physical phones, including automatic count, optional known count, memory and runtime. Review the terms of both model artifacts before redistributing either. Use local aliases and keep the audio and speaker embeddings on device.


## Android preview

In a recording, open **Speaker timeline**, download the two models (39 MiB total), and tap **Analyze locally**. The first version processes clips up to ten minutes on CPU and accepts a known speaker count. Re-running analysis replaces that recording’s timeline and clears its manual names. Tap a speaker label to set or edit the display name. New transcripts may request timestamps in the upload confirmation on compatible endpoints.

Opening the timeline stops library playback before you listen to a turn. The timeline is unavailable during capture. For local audio, the upload dialog leaves segment timestamps off by default. A new transcript without timestamps clears the previous alignment even if the words are identical; the new request keeps its own cost receipt.

The Android 15 arm64 emulator downloaded both models and ran with Wi-Fi and mobile data disabled. The same 56.86-second sample, transcoded to 44.1 kHz stereo AAC, produced 10 turns and five automatic clusters. Requesting four speakers yielded four clusters. A fixture with a timed text segment appeared in the matching row; renaming a speaker updated the stored recording-specific alias. The sample counts do not measure assignment accuracy. Model setup, decoding AAC and rendering the timeline were exercised on the emulator. Paid timestamped transcription, physical-device memory and multilingual diarization accuracy remain unverified.

The preview fetches the segmentation model from [the pinned pyannote conversion](https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/tree/9403a6902bb58e3d5ae8c7e77c3422de279db2e0) and the embedding weights from the upstream sherpa-onnx release. Both downloads are verified by size and SHA-256. The segmentation archive includes the CNRS MIT license, the [3D-Speaker model card](https://www.modelscope.cn/models/iic/speech_eres2net_base_sv_zh-cn_3dspeaker_16k) declares Apache 2.0, and the Android package carries runtime and model license notices under assets/licenses/speaker-analysis.
