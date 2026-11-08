# :studio_microphone: RecorderApp

An Android audio recorder app designed to make capturing, editing, and managing recordings simple and intuitive.

## 💁 About

**RecorderApp** is a full-featured audio recorder that supports multiple codecs (**AAC**, **AMR**, and **Opus**) across configurable quality levels. Recordings continue seamlessly in the background so you never miss a moment.

Listen to your recordings with the `built-in player` or control playback directly from the media notification. Need to remove unwanted sections? The integrated `audio editor` lets you trim and cut clips with ease. You can also organize recordings with `categories`, mark key moments with `bookmarks`, and preview live on-device speech-to-text `transcription`.

### :building_construction: Features

Here are the key features of the app:

- :musical_keyboard: **Effortless Recording**: Start, pause, and resume recordings with a single tap.
- :chart_with_upwards_trend: **Real-Time Visualization**: Watch live amplitude waveforms as you record or play back audio.
- :speaking_head: **On-Device Transcription**: Preview real-time speech-to-text powered by downloadable Vosk models.
- :loop: **Background Recording**: Keep recording even when switching apps or locking your device.
- :bellhop_bell: **Notification Controls**: Control both recording and playback directly from notifications without returning to the app.
- :file_cabinet: **File Management**: Rename, share, trash, or restore your recordings, and browse recordings from other apps on Android 12 (`API 31`) and above.
- :package: **Category Management**: Group recordings into custom categories so they are easy to find.
- :record_button: **Built-in Player**: Listen to recordings in the app or open external `audio/*` files, complete with playback speed controls and media notifications.
- 👨‍🍳 **Built-in Editor**: Trim and cut sections of your recordings or external audio files with undo/redo support.
- :bookmark: **Bookmarks**: Add timestamped bookmarks during recording or playback and export them as a CSV file.
- :eight_spoked_asterisk: **Widgets and Shortcuts**: Quickly start recording or browse recent files using home-screen widgets and app shortcuts.

## 📷 Screenshots

Here are some screenshots of the app:

<p align="center">
   <img src="./screenshots/recorder_base_framed.png" width="24%" />
   <img src="./screenshots/recordings_framed.png" width="24%" />
   <img src="./screenshots/player_base_framed.png" width="24%" />
   <img src="./screenshots/app_settings_framed.png" width="24%" />
   <img src="./screenshots/recordings_search_screen_framed.png" width="24%" />
   <img src="./screenshots/edit_screen_normal.png" width="21%" />
   <img src="./screenshots/player_bookmarks_framed.png" width="24%" />
   <img src="./screenshots/app_widget_preview_framed.png" width="24%" />
</p>

Browse the [screenshots](./screenshots) directory for more.

## :safety_pin: Permissions

The app requests the following core permissions to record and play audio:

- :microphone: **Record Audio**: Required to capture voice and sound from the microphone.
- :musical_note: **Music and Audio Access**: Required to save and read audio recordings on the device.
- :bell: **Notifications**: Used to display recording status and playback controls in the notification shade.

The following permissions are optional and not required for core recording or playback:

- :telephone_receiver: **Phone State**: Used to automatically pause or handle recordings during incoming phone calls.
- :world_map: **Location**: Used to embed location metadata into supported containers (such as `AAC` and `3GPP`).

## :new: What's new

**RecorderApp 1.5.0** builds on the 1.5.0 beta with improvements across transcription, audio
visualization, recording, and editing:

- Preview real-time, on-device speech-to-text with downloadable Vosk models and improved download
  progress and reliability.
- Added Turkish, German, and Spanish localizations.
- Improved audio visualizations, including more accurate decoding and cached visualization data.
- Improved editor validation, recorder service lifecycle handling, and bookmark timing feedback.
- Added support for opening audio links and `audio/*` content from other apps.
- Updated build tooling and dependencies, expanded automated tests, and added benchmarking and
  baseline-profile groundwork.

## :next_track_button: What's next

Add support for lossless recording formats, giving users the option to record without lossy
compression.

If you have any new ideas or suggestions for enhancements, feel free to open a
new [Issue](https://github.com/tuuhin/RecorderApp/issues) on GitHub.

## :hammer_and_wrench: Getting Started

Follow these steps to build and run the project locally:

1. **Clone the Repository**

   ```bash
   git clone https://github.com/tuuhin/RecorderApp.git
   ```

2. **Open the Project**
   Open the cloned directory in Android Studio.

3. **Build and Run**
   Build and run on an Android device or emulator running Android 10 (`API 29`) or higher.

### :woman_cook: Contributing

Contributions from the community are always welcome:

- Fork the repository.
- Create your feature branch (`git checkout -b feature/YourFeature`).
- Commit your changes (`git commit -am 'Add some feature'`).
- Push to the branch (`git push origin feature/YourFeature`).
- Open a pull request targeting the `dev` branch of the main repository.

### :curly_loop: Feedback and Support

If you encounter a bug, have a feature request, or want to share feedback, please open a new
[Issue](https://github.com/tuuhin/RecorderApp/issues) on GitHub. Your feedback is always appreciated!

### :end: Conclusion

Thank you for checking out RecorderApp! A lot of time and care went into building this project, and I hope you find it useful.
