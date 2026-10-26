# Ask

One question, one answer, on the Light Phone III. Ask Claude something, read a short plain-text answer, ask one follow-up if you need it, and that's it. No chat history to scroll back through.

- A daily limit (5 by default) keeps it intentional.
- Uses your own Anthropic API key, billed to your account. Default model Claude Opus 5.5; Sonnet 5.5 and Haiku 5.5 can be chosen in settings.
- Questions are sent to Anthropic. Only the last question and answer are kept on the phone. The key is encrypted with a key in the Android Keystore.
- Setup from a computer: open the Tool Manager on the same Wi-Fi, choose Ask, and paste the key. You can also type it on the phone.

## Build

This repo holds only the `tool/` module. Copy it into a checkout of [light-sdk](https://github.com/lightphone/light-sdk), replacing its `tool/`, then:

```sh
./gradlew :tool:testDebugUnitTest :tool:assembleRelease
```
