# Cards Designer Pro (مصمم البطاقات برو)

[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com/)
[![Language](https://img.shields.io/badge/Language-Kotlin-orange.svg)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**Cards Designer Pro** is a powerful Android application engineered for local Wi-Fi Hotspot network operators (MikroTik), and network managers. It enables network owners to effortlessly transform raw subscriber credentials (usernames, passwords, limits) into professional, print-ready PDF vouchers and internet cards in seconds.

---

## 🌟 Key Features

### 🎨 1. Template Editor
- **Dual-Sided Card Design**: Full design control over both **Front** and **Back** sides of cards with live interactive preview and flip animation.
- **Rich Graphic Elements**: Add customizable text fields, custom logos/images, QR codes, geometric shapes (rectangles, circles, lines), and built-in asset packs.
- **Custom QR Generator**: Supports custom shapes, color palettes, and embedded center logos with optional tinting.
- **Dynamic Placeholders**: Map template elements dynamically to raw data fields like `Username`, `Password`, `Date`, and custom columns.
- **Advanced Styling & Typography**: Custom Arabic & Latin fonts, full hex color picker, transparency/opacity control, text alignment, and Z-index layering.

### ⚡ 2. High-Performance PDF Export Engine
- **Ultra-Fast Batch Processing**: Powered by **Apache PDFBox Android** running in a dedicated background Foreground Service (`PdfExportService`). Processes thousands of card credentials into high-resolution PDFs in seconds without UI freezing.
- **Multi-Format Data Import**: Seamlessly imports subscriber data from **CSV**, **Excel (.xlsx/.xls)**, and **PDF** files.
- **Smart Column Mapping**: Automatically detects and maps file columns to card placeholders with a user-friendly field mapping dialog.
- **Flexible Page Layout Calculator**: Customize page size (A4, Letter), grid dimensions (columns × rows), card margins, horizontal & vertical spacing.
- **Double-Sided Printing**: Supports duplex printing alignment with **Flip on Long Edge** and **Flip on Short Edge** controls.

### 🌐 3. Local Wi-Fi Network Sharing
- **Built-in NanoHTTPD Server**: Hosts a lightweight HTTP web server directly on the Android device over local Wi-Fi.
- **Zero-Internet Transfer**: Share exported PDF card batches and custom template files directly to other phones, PCs, or printers on the same local network without requiring an active internet connection.

### 🔍 4. Unused Cards Extractor (PalTel & Local Network Tool)
- **Specialized Network Utility**: Purpose-built for network owners (e.g., PalTel/Gaza Wi-Fi hotspot managers).
- **Automated Card Filtering**: Cross-references master card batches against active/sold user logs to isolate and extract unused credentials, eliminating waste and keeping inventory organized.

### 📦 5. Template Package Management
- **Pre-designed Templates**: Includes built-in professional templates ready for instant customization.
- **Batch Import/Export**: Export and import card template packages (`.cdp` zip files) across devices.

### 📄 6. Integrated PDF Viewer
- Native PDF preview supporting Android 12+ Jetpack PDF Viewer and backport pure-Kotlin PDF rendering for earlier Android versions.

---

## 🛠️ Architecture & Tech Stack

- **Language**: 100% Kotlin
- **Architecture**: MVVM (Model-View-ViewModel) with Android Architecture Components
- **UI & Views**: Custom Canvas Views (`CardCanvasView`, `TemplateRenderer`), ViewBinding, Material Components
- **Data Serialization**: `kotlinx.serialization`, Custom `.cdp` Zip Manager
- **File Parsing**:
    - `fastexcel` (Excel XLSX parsing)
    - `opencsv` (CSV parsing)
    - `pdfbox-android` (PDF text/table extraction & generation)
- **PDF Generation**: `pdfbox-android`
- **QR Code Generation**: Custom QR generator powered by ZXing with custom shapes & logo overlays
- **Local Network Server**: `NanoHTTPD` for offline HTTP network distribution
- **Image Handling**: `Coil`, `AndroidSVG`

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug (2024.2.1) or newer
- JDK 17
- Android SDK 36 (Minimum SDK 26 - Android 8.0)

### Building the App
1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/CardsDesignerPro.git
   cd CardsDesignerPro
   ```
2. Create a `key.properties` file in the root directory if building release builds, or run debug build via Gradle:
   ```bash
   ./gradlew assembleDebug
   ```

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
