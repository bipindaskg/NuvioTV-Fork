# Low-RAM (1 GB) change set

Only one file is changed: `app/src/main/java/com/nuvio/tv/NuvioApplication.kt`.

- `androidTvChannelSyncService.start()` is skipped on boxes in the 1 GB class
  (`BufferSizing.autoBufferMbForRam(totalMem) == BUFFER_1GB_BOX_MB`, i.e. under about 1.15 GiB reported).
  This removes the periodic launcher-channel job and its cache observers. Unknown memory keeps the sync.
  Side effect: the Nuvio rows on the Android TV launcher home screen stop updating on such boxes.

Not compiled or run here (no Android SDK/network in my sandbox). Build it in your CI first.

## Install the right APK
Use the `armeabi-v7a` APK unless `adb shell getprop ro.product.cpu.abi` says `arm64-v8a`.

## No-code settings for a 720p 1 GB TV (the app already defaults to the cheap path for blur, buffer and thumbnails)
- Settings > Appearance: turn off the intro animation and sound; set Settings presentation to Minimal and Settings background to a plain colour (Midnight or Charcoal) instead of Posters.
- Keep Visual quality on Automatic or set it to Performance.
- Leave seek thumbnails Off, and keep "Search sources before Play" off.
- Playback: leave buffer on Automatic (100 MB on 1 GB boxes) and connections on the default.
