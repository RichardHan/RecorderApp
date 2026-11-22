"""Run speaker diarization on mono 16 kHz audio using local ONNX weights."""
import argparse
import json
from pathlib import Path

import sherpa_onnx as sherpa
import soundfile

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("audio", type=Path)
    parser.add_argument("--segmentation", type=Path, required=True)
    parser.add_argument("--embedding", type=Path, required=True)
    args = parser.parse_args()
    audio, rate = soundfile.read(args.audio, dtype="float32")
    config = sherpa.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa.OfflineSpeakerSegmentationPyannoteModelConfig(model=str(args.segmentation))
        ),
        embedding=sherpa.SpeakerEmbeddingExtractorConfig(model=str(args.embedding)),
        clustering=sherpa.FastClusteringConfig(num_clusters=-1, threshold=0.5),
    )
    if not config.validate():
        parser.error("Check the segmentation and embedding model paths")
    engine = sherpa.OfflineSpeakerDiarization(config)
    if rate != engine.sample_rate or audio.ndim != 1:
        parser.error(f"Provide a mono {engine.sample_rate} Hz audio file")
    turns = engine.process(audio).sort_by_start_time()
    identifiers = {}
    rows = []
    for turn in turns:
        speaker = identifiers.setdefault(turn.speaker, len(identifiers) + 1)
        rows.append(dict(speaker=speaker, start=round(turn.start, 3), end=round(turn.end, 3)))
    print(json.dumps(rows, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
