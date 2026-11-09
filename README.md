# GeoSnap

> [!WARNING]
> **Verification Sandbox:** This repository contains unverified, AI-generated baseline code (created via Google AI Studio). It is published in this raw state on purpose, as a starting point for practicing software verification, CI and agentic workflows. Expect rough edges.

An Android app that reads the GPS location stored in a photo and turns it into a shareable map card with a pin at that spot.

## What it does

- Opens a JPEG or HEIC photo and reads its EXIF GPS coordinates
- Generates a 1080 × 1080 map image from OpenStreetMap tiles, with a pin at the photo's location
- Adds a caption with the place name (reverse geocoding via Nominatim) or, as a fallback, the coordinates
- Lets you adjust the zoom, then save the card to the gallery or share it

## Tech stack

- **Language:** Kotlin
- **UI:** Jetpack Compose, Material 3
- **Architecture:** single screen with a ViewModel; EXIF reading and map rendering in separate classes (`ExifLocationReader`, `MapCardGenerator`)
- **Networking:** OkHttp for map tiles and reverse geocoding
- **Build:** Gradle (Kotlin DSL), min SDK 24, target SDK 36

## Verification roadmap

- [x] **Publish the raw prototype** exported from Google AI Studio
- [ ] **Build outside AI Studio:** compile and run the app locally from this repository
- [ ] **Clean up the AI Studio leftovers:** replace the `com.example` package name, remove unused dependencies and the unused Gemini placeholder config
- [ ] **Unit tests** for the core logic: EXIF GPS parsing and the coordinate-to-map-tile math
- [ ] **CI:** GitHub Actions running the build, lint and tests on every pull request
- [ ] **Respect the OpenStreetMap and Nominatim usage policies** (attribution, request limits, identifying User-Agent) before any wider use

## Building

Open the project in Android Studio and run the `app` configuration on a device or emulator (Android 7.0 / API 24 or newer). The app needs internet access to load map tiles and place names.

## Map data

Map tiles and place names © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors.
