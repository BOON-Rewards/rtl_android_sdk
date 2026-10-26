[Official RTL developer documentation](https://crowdplay.gitbook.io/rtl-developer-documentation)

### Crisp haptic clicks

The `hapticPlay` bridge supports transient `impact` and sustained `continuous`
events. For a single crisp click, send this payload (milliseconds for `at`):

```json
{"version":1,"events":[{"at":0,"type":"impact","intensity":1,"sharpness":1}]}
```

Impacts have no duration. Intensity and sharpness range from 0 to 1; omitted
values default to 0.5. iOS plays impacts as Core Haptics transient events.
Android approximates impacts with a 30 ms pulse and ignores sharpness; intensity
control depends on the device. Check the advertised haptic capabilities before
requesting playback. Existing version 1 vibration patterns remain supported.

### Background location is opt-in

SDK initialization does not request location permissions. Hyperlocal Offers
requests permissions only after the host calls `enableLocationFeatures(activity)`.
The location add-on does not declare background location on behalf of the host.
On Android 10 and later, hosts using background geofencing must explicitly add
this permission to their application manifest before enabling the feature:

```xml
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

Without that declaration, RTL does not request background location permission.
Core foreground location requests do not require background access.
