# PodiumClass Native Android App 📱

Native Android mobile companion app for **PodiumClass v2**, built with **Kotlin 2.0+**, **Jetpack Compose (Material 3)**, and **LiveKit WebRTC SFU**.

## Features
- **Dual-Role Support**: Full feature set for both **Faculty Hosts** and **Students**.
- **Meet-Style Classroom Stage**:
  - LiveKit WebRTC video and audio streaming.
  - Active speaker detection & spotlight pinning.
  - Aspect ratio toggle (Fit vs Fill).
  - Background audio & Android **Picture-in-Picture (PiP)**.
- **Automated Anti-Cheat Attendance Checker**:
  - **Host**: Pre-class setup in Create Class dialog; live in-class manager modal to reconfigure target checks (`+1`, `+2`), modal active duration (15s, 30s, 45s, 60s), check intervals, pause/resume, and view live student attendance roster.
  - **Student**: Full-priority non-dismissible prompt with synthesized audio chime, haptic vibration alert, urgent countdown progress bar, and one-tap "I AM PRESENT" button logging directly to PostgreSQL.
- **Interactive Control Bar**:
  - Mic, Camera, and Front/Rear camera switching.
  - Upward-anchored sub-popup for emoji reactions and hand raising.
  - Real-time in-class chat drawer.

## Architecture & Project Structure
- `app/src/main/java/online/podiumclass/app/`:
  - `data/api/`: Retrofit REST client adhering to the Document Object contract (`{ "document": ... }`).
  - `data/livekit/`: LiveKit SFU Room Manager handling media tracks and reliable data channels.
  - `data/storage/`: DataStore for persisting JWT credentials and server endpoint configuration.
  - `ui/screens/classroom/`: Classroom stage, control bar, student verification prompt, host attendance manager, and chat drawer.
  - `ui/screens/dashboard/`: Session list and class creation dialog with automated attendance options.
  - `ui/screens/auth/`: Google & Dev authentication.

## How to Build & Run
1. Open the `android/` directory in **Android Studio Meerkat / Ladybug** (or later).
2. Ensure you have **JDK 17** or **JDK 21** selected in `Settings > Build, Execution, Deployment > Build Tools > Gradle`.
3. Start the Django backend (`python manage.py runserver 0.0.0.0:8000`) and LiveKit server.
4. Run the app on an Android Emulator or physical device (Android 8.0+ / API 26+).
   - On Android Emulator, the app connects to `http://10.0.2.2:8000` by default.
   - On physical devices, tap the **Settings** pill on the sign-in screen to set your computer's local IP (e.g. `http://192.168.1.x:8000`).
