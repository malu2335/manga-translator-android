# User Guide

> This guide is maintained for the latest version of the app. Earlier versions may have different screens or features.

For feedback and discussion, join the [Discord server](https://discord.gg/qxfC2kqfr).

---

## Main Translation Workflow

### 1. Import a manga

On the **Library** screen:

- Tap **+** in the bottom-right corner to create a folder, then add manga images to it.
- Tap **Import Manga Folder** to import a complete folder.
  - A folder containing chapter subfolders is imported as a collection with child chapters.
  - A folder containing images directly is imported as one manga.
- Tap **Import CBZ/ZIP/PDF** to import an archive or PDF. The app shows an import progress message.
- Ensure that image filenames match their intended reading order, such as `1.jpg`, `2.jpg`, and `3.jpg`.

### 2. Configure an API

The app requires an API from a supported AI provider. See the [API Setup Guide](./API_Guide_EN.md) if you do not have one yet.

> The app UI language determines the target language. Simplified Chinese, Traditional Chinese, English, Brazilian Portuguese, and Russian UIs translate into their respective languages.

In **Settings**, enter the API details for your provider:

1. **API Format**: select the format specified in the provider section of the API guide.
2. **API URL**: enter the provider's base URL. The app appends `/chat/completions` for **OpenAI compatible** and `/responses` for **OpenAI Responses** when needed.
3. **API Key**: paste the key generated in the provider dashboard. Keep it private.
4. **Model name**: enter the exact model ID shown by the provider, or select one with **Get Model List** when available.

Return to the library after saving the settings.

### 3. Translate

1. Set the source language in the folder and tap **Translate Folder**. Japanese, English, Korean, French, Spanish, Portuguese, German, Italian, Russian, and Chinese are supported. You can also translate a whole collection.
2. Wait for the translation to finish, or tap **Start Reading** after preprocessing to read while pages continue translating.
3. A high-priority notification is sent when background translation succeeds, fails, or is canceled. Tap it to return to the library.

> The app automatically switches works that resemble webtoons to **Webtoon Scroll**. Change this in the folder's reading settings when necessary.
>
> **Full-text fast translation** processes the whole work first, so the first page can take longer to appear for large folders. Turn it off to start reading sooner with ordinary page-by-page translation.

### 4. Read and adjust bubbles

- Drag a translated bubble to move it. Changes are saved automatically.
- Double-tap the page to enter or leave zoom mode. In zoom mode, use the cancel control or the `+` and `-` controls to fine-tune bubbles.
- To jump to a page, tap its image filename in the library folder.
- To retranslate pages, return to the library and long-press an image to enter selection mode. Select the pages and tap **Retranslate Selected**. Use **Select All** to retranslate every page; there is no need to delete the manga.
- To add a missing text area, tap the edit button in the top-right corner, tap **+**, place an empty bubble over the text, then confirm with the green checkmark. Webtoon mode allows bubbles to span pages.
- If full-text fast translation is too slow, turn it off to use concurrent page-by-page translation instead.
- Adjust normal or free-text bubble opacity under **Normal Bubble Settings** when bubbles cover too much artwork. Floating-window bubbles have separate settings.
- Customize the global translation style in Settings, or set a style for a folder. Chapters in a collection share the collection's style. Turning off **Follow Global Style** and leaving the field empty adds no style instruction.
- Upload a custom font or enable bold text under **Font Settings**. Normal and floating-window bubbles share this font setting.

---

## Floating-Window Translation

On the Library screen, tap **Floating Translation**. Grant overlay and screen-capture permissions, then choose the translation language for the session. The floating button uses these default gestures; you can reassign single, double, long, and triple taps in **Floating Translation Settings**:

| Action | Result |
| --- | --- |
| Tap the floating button | Recognize and translate text currently on screen |
| Double-tap the floating button | Clear all bubbles |
| Long-press the floating button | Open the menu |

The long-press menu includes:

- **Edit mode**: the overlay receives touch input. Drag bubbles, long-press to delete one, or tap **Add Bubble** and draw a rectangle for text the detector missed. New bubbles are translated after confirmation.
- **Swipe translation**: enter edit mode directly and select regions manually without automatic detection.
- **Translation detection region**: draw and confirm a region on the screen. It takes effect on the next translation; you can also restore the full screen.
- **Exit**: close the floating window.

Floating-window edits exist only for the current session. They reset after a new recognition pass or when the floating window closes. If the screen is blank or screen capture is restricted, the app stops recognition and shows a message. For other missing results, check screen-capture permission and API settings.

---

## Other Settings

### OCR settings

Open **OCR Settings** from Settings:

- **Use local OCR** runs the bundled offline recognition engine.
- Disabling local OCR uses an online OCR API and requires its URL, key, and model.
- Online OCR can support multiple languages when the selected model supports them.
- Increase **OCR API timeout** when needed. The allowed range is 30 to 1200 seconds.

### Custom request parameters

Parameters apply to the main provider. A provider cannot have duplicate parameter names.

### AI request settings

**Pages per request** accepts 1–200; 1 disables page merging. Ordinary translation and full-text fast translation in folder, collection, and batch tasks can combine several pages in one AI request. Results are still saved page by page. Large values may exceed the model's output limit.

### Folder translation options

- **Vision-language full-page translation**: enable it in a folder to send an annotated full page to the main image-capable model and skip OCR. Long images are sent in sections. It cannot be enabled together with **Full-text fast translation**.
- **Glossary Processing**: ordinary and vision-language full-page translation can extract and update terms when enabled. When disabled, existing terms can still be used, but no new terms are saved. Full-text fast translation always enables glossary processing.

### Floating translation settings

Configure a separate API URL, key, and model for floating translation. Leave these blank to use the main API settings.

- **Vision-language full-page translation** sends an annotated image of the page's bubble and text regions to an image-capable model and skips OCR. Long images are sent in sections. Set its concurrent translation count separately.
- **Proofreading mode** enters edit mode after every recognition pass.
- **Auto-clear bubbles** clears bubbles automatically when the page changes.
- **Floating-button gestures** let you change what single, double, long, and triple taps do.

---

## Tips

- Export a folder as CBZ, PDF, or images. The default export directory is `Documents/manga-translate`.
- Clear app cache to remove the floating-translation cache. Saved manga page translations remain available and are not automatically translated again. Use **Retranslate Selected** to replace them.
- Use **Export Manga and Settings** in Settings to create a backup, and **Import Manga and Settings** to restore it.
- Open a collection and tap **Import Chapters** to import child chapters. You can select several chapters at once; empty or duplicate folders are skipped.
