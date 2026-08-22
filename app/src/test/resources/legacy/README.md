# Legacy Office test fixtures

These read-only fixtures come from the official Apache POI test corpus on the `trunk` branch:

- `with_textbox.ppt`: `test-data/slideshow/with_textbox.ppt`
- `lists-margins.doc`: `test-data/document/lists-margins.doc`

They are used only by JVM unit tests to verify that the Android-shaded POI runtime can read real
OLE2 Word and PowerPoint files without invoking unsupported desktop/AWT drawing paths.

Apache POI and its test data are distributed under the Apache License 2.0.
