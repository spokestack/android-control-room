# Spokestack Control Room

This is a simple Android app that demonstrates the core features of [Spokestack Android](https://github.com/spokestack/spokestack-android). It was created by cloning the [Android skeleton](https://github.com/spokestack/android-skeleton) project and modifying `MainActivity`.

---
## Prerequisites/Notes


### Wakeword detection

Just like the skeleton project mentioned above, this demo is configured to use wakeword detection provided by [TensorFlow Lite](https://www.tensorflow.org/lite) models. These models are trained to respond to the "Spokestack" wakeword. They must be downloaded and placed in `src/main/assets`. The file names should not be changed.

```
wget https://s.spokestack.io/u/hgmYb/detect.tflite -P app/src/main/assets
wget https://s.spokestack.io/u/hgmYb/encode.tflite -P app/src/main/assets
wget https://s.spokestack.io/u/hgmYb/filter.tflite -P app/src/main/assets
```

### Speech recognition

To avoid the need to obtain API credentials to use this demo, Android's on-device ASR API is used. Support for this API, however, is device-dependent. See the [device compatibility chart](#device-compatibility) at the bottom of this document to see if support for your device has been tested. Note that testing ASR on the emulator will not work since [it cannot record audio](https://developer.android.com/guide/topics/media/mediarecorder).

If you're unable to get on-device ASR to work, you can try using a different ASR provider. You'll need to set up an account with [Google Cloud Speech](https://cloud.google.com/speech-to-text) or [Azure Speech Services](https://azure.microsoft.com/en-us/services/cognitive-services/speech-services/), but both currently have free trials. See the [SpeechPipeline configuration documentation](https://spokestack.io/docs/Android/speech-pipeline) for info about changing the configuration used in this project.

---

## Description

Here's a screenshot of `MainActivity` in action, so we can talk about what's going on.

<img src="images/screenshot.png" alt="Spokestack Control Room screenshot" width="400"/>

A `Spokestack` instance is built and started after the audio recording permission is granted, so the microphone is active, and Spokestack is waiting to hear its wakeword. The text next to the `Voice` label will display `Active` whenever _any_ speech is detected (otherwise known as [voice activity detection](https://en.wikipedia.org/wiki/Voice_activity_detection)). Random room noise will occasionally be detected as speech.

If the word "spokestack" is detected by the on-device wakeword model (or if the `Activate ASR` button is tapped), the text next to the `ASR` label will display `Active`, indicating that speech is being sent to the chosen speech recognition provider. Errors during this process are displayed as toasts at the bottom of the screen. When ASR is done detecting speech (after the user stops speaking for a configurable amount of time; again, see the configuration documentation for more info), the text of the detected speech will be displayed under the `Transcript` label.

The bottom part of the screen is for testing Spokestack's NLU and TTS services. If you'd like Spokestack to use the ASR transcript, toggle the `Update from transcript` switch; otherwise you can enter the text to be classified or spoken into the text field just above the `Speak` button.

The NLU model included with the repository contains intents from a [Minecraft sample app](https://github.com/spokestack/minecraft-skill-android), originally ported from [an Alexa sample skill](https://github.com/alexa/skill-sample-nodejs-howto). To see the list of intents it's capable of recognizing, look in `src/main/assets/nlu.json`. The main intent is `RecipeIntent`, which responds to utterances similar to "How do I make a ____", where the blank contains an item in the Minecraft game but can theoretically be any noun.

That's it! Happy testing!

---

## Device compatibility

This chart lists physical devices on which Android ASR has been tested by either the Spokestack team or our community. If you have a device that is not listed, please try it out and [submit a PR](https://github.com/spokestack/android-control-room/pulls) with your results!

| Device                 | API Level | ASR working?  |
| ---------------------- | --------- | ------------  |
| Moto X (2nd Gen)       |        22 | &#10060;  `*` |
| Lenovo TB-X340F tablet |        27 | &#9989;       |
| Pixel 1                |        29 | &#9989;       |
| Pixel 3 XL             |        29 | &#9989;       |
| Pixel 3a               |        29 | &#9989;       |
| Pixel 4                |        29 | &#9989;       |

`*` ASR fails consistently with a `SERVER_ERROR`, which seems to indicate that the server used by the device manufacturer to handle these requests is no longer operational.
