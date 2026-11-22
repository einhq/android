# The app keeps nothing of its own by name: the WebView reaches it through a web message listener,
# not a JavaScript interface.

# ML Kit, under Google's code scanner, makes its component registrars by reflection from the names in
# its manifest, but its own rules keep only their classes: without their constructors R8 takes them
# away, ML Kit finds none of its parts, and opening the scanner throws.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
