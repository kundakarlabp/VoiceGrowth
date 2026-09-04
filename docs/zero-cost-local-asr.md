# VoiceGrowth zero-cost local ASR

VoiceGrowth archives original audio to the user-selected private Drive tree first. Local transcription then runs on-device using sherpa-onnx and open model weights.

## Runtime policy

- First-time model installation requires an unmetered network.
- After all required models are installed, transcription requires no network connection.
- Routine inference does not require the device to be charging; it only requires battery and storage not to be low.
- Audio remains private and no paid transcription API is called.
- The selected Drive root is normalized so selecting an existing `VoiceGrowth` folder does not create `VoiceGrowth/VoiceGrowth/...` nesting.
