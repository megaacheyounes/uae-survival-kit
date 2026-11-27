# Build on Windows

Use PowerShell 7, the Android SDK (platform 35 and build-tools 35.0.0), and Python 3. Set `ANDROID_HOME` if your SDK is outside `D:\android\sdk`. The build scripts expect these project-local prerequisites in `.build`:

- Temurin JDK 17 and Gradle 8.11.1, extracted as `jdk-17*` and `gradle-8.11.1`.
- Android NDK r27c in `android-ndk-r27c`. Save the [llama.cpp b12590 source archive](https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/b12590) as `llama-b12590-src.tar.gz` and the [official Android ARM64 release archive](https://github.com/ggml-org/llama.cpp/releases/download/b12590/llama-b12590-bin-android-arm64.tar.gz) as `llama-b12590-bin-android-arm64.tar.gz`. The native build verifies both digests and packages the CPU backends for use in-process.
- A Python virtual environment in `py` with `cmake==3.31.6` and `ninja`. Rebuilding the map directory also uses `osmium==4.3.2` and `shapely==2.2.0`.

Place the pinned map pack at `delivery/UAE-map.zip` or rebuild it using the steps below. [Download the map used by version 1.10](https://github.com/megaacheyounes/uae-field-guide-map/releases/download/2026-11-18-directory/UAE-map.zip)

Run the following commands from the repository root.

```powershell
./tools/native.ps1
./tools/gradle.ps1 :app:assembleRelease
./tools/sign.ps1
```

The signing script reuses the personal keystore in `.private`. Its password is protected by Windows DPAPI for the current user. Retain that folder for in-place updates. Keep it separate from anything transferred to the phone.

## Rebuild offline data

Place Osmosis 0.49.2 under `.build/osmosis` and the Mapsforge 0.31.0 map-writer jar with dependencies at `.build/writer.jar`. Download OpenStreetMap France’s UAE PBF as `.build/uae.osm.pbf` and its matching replication state as `.build/osm-state.txt`. Run `.build/py/Scripts/python tools/map_pack.py`. The map and SQLite index are generated from the same PBF. The manifest inside the ZIP records its SHA-256, source date, attribution, version and both payload checksums. The app validates and stages each replacement before switching to it. Map data © OpenStreetMap contributors, ODbL 1.0. [UAE extract](https://download.openstreetmap.fr/extracts/asia/) · [OpenStreetMap copyright](https://www.openstreetmap.org/copyright)

**Map → Directory** opens the country-wide catalogue, including individual emirates and places tagged in English or Arabic. Browse all entries or choose one of 17 service categories. The quick filters start within 50 km of the map centre. **All UAE** removes the radius. Results show straight-line distance from the map centre and use full-screen pages of 50. **Nearby pins** shows up to 12 at a time. A result opens its coordinates, mapped details and any recorded access restrictions. Back returns to the same list page.

**Field note** saves your latest dated observation for a location on the phone. Reopen it from **Directory → Field notes** even after a map-pack replacement. **Export text** writes a readable copy through Android's file picker. Water categories also link directly to the included water-treatment guide.

The place index uses OSM nodes, ways and tagged multipolygons. It assigns emirates using closed boundary rings in the same extract and preserves enclaves. Where an address is missing, a nearby named locality can be shown with “Near”. Drinking-water points and other water sources have separate categories. A map record describes the source date. Supplies, opening status and water quality can change.

The map uses the field theme in `app/src/main/assets/map`. Zoom and location controls sit on the map. **Layers** switches the map view, opens aerial close-ups, and offers larger labels and the country overview. Map imports and downloads are under **Manage offline maps**. The APK ships a 3 MB coastal backdrop so the original map pack can show the sea and islands offline. Detailed UAE coastlines use the November 17, 2026 pack extract. Regional land outlines and low-zoom land borders use public-domain Natural Earth geometry.

**Map → Layers → Save satellite imagery** saves a complete UAE land imagery pack in app storage. The app can resume a partial transfer and checks the published SHA-256 before activating the extracted Mapsforge tile store. After installation, choose **Satellite** or open **Aerial close-ups**. All seven emirates, Hatta, Khor Fakkan and offshore land are included at zooms 10–14 using the 2021 ESA WorldCover annual Sentinel-2 composite (10 m). At zooms 15–19, three historical aerial surveys cover parts of Al Wagan, Cedre Villas and Al Ghail. Other locations return to the offline street map. Follow posted evacuation directions on site.

The country mosaic contains modified Copernicus Sentinel data (2021) processed by the ESA WorldCover consortium. Eric Horring provided the three aerial surveys via OpenAerialMap. The pack includes credits and a manifest giving the survey dates in UAE local time, footprints and original files. The imagery was clipped, colour-adjusted (Sentinel), projected and resampled under CC BY 4.0. [ESA WorldCover data](https://esa-worldcover.org/en/data-access) · [OpenAerialMap](https://openaerialmap.org/) · [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)

To rebuild the downloadable pack, use the map pack's UAE OSM extract and project GIS environment with rasterio, mercantile and Pillow installed. Run `python tools/satellite_pack.py`, then check the release pin and geographic coverage with `python tools/verify_satellite_pack.py`. The builder reads published GeoTIFFs and generates XYZ tiles.

If a render stops early, rerun its `--step country` or `--step aerial` command before `--step package`. Packaging replaces the existing ZIP only after the new archive closes successfully.

To rebuild the backdrop, install Shapely 2 and osmium in the project virtual environment. Put [Natural Earth 1:10m land](https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_land.geojson) and [1:50m land borders](https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_0_boundary_lines_land.geojson) in `.build` with their original filenames. Their input SHA-256 digests for this release are `1ac90796408bc6ad6911d69448485d3c4dbf2190370080368a09976e1c9f7416` and `2faac4f6b34386f3d21b6e018cf151f241f00e5c936d44dd17d7d9bfb147fa48`, respectively. Run `.build/py/Scripts/python tools/coast_map.py` before assembling the app.

The optional model is the official Qwen2.5‑1.5B‑Instruct Q4_K_M at revision `91cad51170dc346986eccefdc2dd33a9da36ead9`. Its required SHA-256 is `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e`. [Model and license](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF)

Run `python tools/verify_delivery.py` to check the transfer files, model hash, APK contents, SQLite integrity, Arabic search and geographic coverage. The field guide records a source authority, URL and review date for its published contacts and guides.

## Update the printable shelter cards

After editing the Android strings, run `python tools/shelter_card.py`. Print each generated HTML file to A4 PDF with browser headers and footers disabled, then replace the corresponding shelter-card PDF in `app/src/main/assets`.
