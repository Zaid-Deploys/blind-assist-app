# Blind Assist

An offline, real-time assistance app for visually impaired users, built for Android and tuned for Indian conditions (Indian currency, Devanagari/Hindi text, and dense Indian street scenes).

Everything runs fully on-device -- no internet connection required, no data ever leaves the phone.

## Why

Most existing accessibility tools assume reliable internet or expensive hardware. This project is built for real-world conditions in India: unreliable connectivity, affordable Android devices, and everyday environments most global apps don't account for.

## Features (in progress)

- [x] Text reading -- point the camera at any text (signs, labels, books) and it's read aloud instantly, fully offline (Google ML Kit Text Recognition)
- [ ] Object and people recognition -- narrates what's around you
- [ ] Obstacle/hazard detection -- warns before you'd walk into something
- [ ] Currency identification -- recognizes Indian currency notes

## Tech Stack

- Language: Kotlin
- UI: Jetpack Compose
- Camera: CameraX
- Text Recognition: Google ML Kit (on-device, offline)
- Speech: Android TextToSpeech

## Status

Actively in development. Built step by step as a real-world accessibility project.

## Setup

1. Clone the repo
2. Open in Android Studio (Kotlin, minimum SDK 24)
3. Run on a physical Android device (camera features don't work well on emulator)
