# Google AI Edge Gallery handoff for VoiceGrowth

## Supported
VoiceGrowth launches the installed Gallery Audio Scribe screen using the
documented Android deep-link routing:
`com.google.ai.edge.gallery://llm_ask_audio`.

An additional link opens Gallery's model manager:
`com.google.ai.edge.gallery://global_model_manager`.

The optional native Gemma comparison APK can be opened separately.

## Limitations
The stock Gallery app does not expose a documented public service for other
apps to command speech transcription, retrieve its transcript, or reuse its
privately downloaded Gemma model files. These buttons open Gallery, but do
not transfer an audio file, return text, or start background processing.

Select short synthetic or authorized audio within the Gallery user interface.
The existing VoiceGrowth audio archive can be opened via Android Files if the
Google Drive DocumentsProvider exposes the selected folder. Do not create an
unprotected copy of private audio to work around file-picker limitations.

The separate Gemma LiteRT benchmark from PR 22 uses its own imported
model files; it does not access Gallery's private storage. No extra
subscription or paid API is involved.

## Verification
1. Install a preview build alongside the production recorder.
2. Open Gallery Audio Scribe from VoiceGrowth Home and Settings.
3. Confirm the installed Gallery app opens its Audio Scribe task.
4. Verify the fallback message if Gallery is absent.
5. Verify that VoiceGrowth recording, Drive archiving, and the clinical
   dashboard are unaffected.

Source: Google's Gallery AndroidManifest, GalleryNavGraph and built-in
task ID LLM_ASK_AUDIO = llm_ask_audio, from
https://github.com/google-ai-edge/gallery .
