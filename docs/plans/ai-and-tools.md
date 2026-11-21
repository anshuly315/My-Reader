# AI and PDF tools (combining FlowFolio, Librera, Stirling-PDF, Brief You, Feeder and erhu)

## What was decided, and why

| Source | Taken | Left |
|---|---|---|
| **FlowFolio** | The app itself: Compose UI, reader, dictionary (Define), ML Kit Translate, Narrator, OCR | |
| **Brief You** | On-device AI: Gemma through LiteRT-LM (`.litertlm`, GPU then CPU, pinned and checksummed download) | Its RSS reader and summary UI |
| **Feeder** | Bring-your-own-key online AI: one OpenAI-compatible provider (OpenAI, Gemini, OpenRouter, Groq, custom https) | Its feed code, DeepL translation |
| **erhu** | Nothing. Its AI is an embedding index for searching a *music* library (VYBE), which has no use in a reader | Everything |
| **Stirling-PDF** | The tool list, re-implemented on the phone with PDFBox for Android (17 tools) | The Spring server and the 30+ tools that need server software (Office conversion, PDF/A, redaction, signing, compare, OCR-to-searchable-PDF) |
| **Librera** | Not yet: see "Not done" | |

Anthropic's own Messages API is a second online provider, since Feeder's covers only the OpenAI dialect.

On-device versus online: on-device is private and free but needs about 2.6 GB and a recent phone, and answers are
slower and shorter; online is faster and stronger and sends the selected passage (plus about 400 characters around
it) to the provider the reader chose. Both are offered; on-device is the default choice in Settings → AI.

## Where things are

- `core/ai`: `AiProvider` (OpenAI-compatible, Anthropic, on-device), `AiService.run/chat`, `AiPrompts`,
  `AiSettingsStore` (key sealed by `SecretBox`, an Android Keystore AES-GCM key), `OnDeviceModel`.
- `core/pdftools`: `PdfToolkit`, `PageRanges`.
- `feature/tools`: Tools tab (`PdfToolsScreen`), `AiSettingsScreen`, `AiAssistantSheet`.
- Reader: `Ask AI` in the selection menu (`ReaderAnnotating`, `ReaderAnnotations`); the app opens Settings → AI through
  `LocalOpenAiSettings`.

## Not done

- **Librera's engine and features.** Librera is 1,600 Java files on View-based screens with a native MuPDF build; it
  cannot be re-skinned into FlowFolio's Compose design in one step. The formats it opens that FlowFolio does not
  (FB2, MOBI, DJVU, CBZ/CBR, TXT/RTF/HTML), OPDS catalogues, Librera's cloud sync and its TTS-by-file are the next
  work. The cleanest route is converting those formats to EPUB/PDF on import, as FlowFolio's converter already does for
  fixed-layout PDFs.
- AI translation inside the existing Translate panel (it still uses ML Kit); Ask AI → Translate does the job today.
- The reader and app edits were written against the code but **could not be compiled in the build sandbox**: FlowFolio's
  PDFium, ONNX Runtime and Tesseract pieces download from GitHub release URLs the sandbox blocks. Build the app on your
  machine (`./gradlew :app:assembleEssentialDebug`) and run the app tests (`DestinationsTest` was updated for the fifth tab).
- Network: Android blocks plain `http://`, so a server on your own computer (Ollama, say) needs https in front of it.
