# Search inside recordings on Android

A search from the OpenRouter Recorder library should return recordings containing the typed words in their saved transcripts. Each result shows a short excerpt around the first match. Opening a result takes the user to the matching passage. A timestamp becomes a playback target when timed segments exist.

## Scope for the first implementation

- Search on the device, including previously saved transcripts, without sending queries or transcripts to an API.
- Search English, Traditional Chinese, Simplified Chinese and Japanese. Verify substring matches for CJK text without spaces, case-insensitive English, and literal punctuation. Treat empty input as the normal library.
- Update results after a new transcription, replacement or deletion. Display one result per recording and cancel stale queries when the user keeps typing.
- Keep the audio and its transcript distinct. A recording without text becomes searchable by content after transcription. Typing in search must never start a billable transcription.
- Measure indexing time on a library with several hours of transcripts before choosing an index. If an index is added, keep it in app-private storage and exclude it from backups.

## Review examples

Search for 預算 in a Traditional Chinese meeting, 预算 in a Simplified Chinese meeting, 見積 in Japanese, and budget in a mixed-case English sentence. Verify excerpts, navigation, empty results, app restart, replaced text and deletion while offline.
