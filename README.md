# RoamersPlus

An add-on mod for [Roamers](https://www.curseforge.com/minecraft/mc-mods/roamers) that enhances roamer behavior with sustainability features.

## Features

### 1. Sapling Spawning
When a roamer spawns, they receive 8 of each sapling type used for their biome variant's buildings:

| Biome Variant | Saplings Received |
|---------------|-------------------|
| Plains | 8 Oak + 8 Birch |
| Taiga | 8 Spruce |
| Jungle | 8 Jungle + 8 Oak |
| Savanna | 8 Acacia + 8 Oak |
| Cherry | 8 Cherry |
| Desert | None (uses sandstone) |
| Badlands | None (uses red sandstone) |
| Arctic | None (uses snow) |

### 2. Pre-Excavation Sapling Scattering
Before roamers start clearing land for their builds, they scatter their saplings throughout the tree-chopping radius. This helps ensure trees will regrow in the area. Saplings that cannot be placed (bad terrain) are kept in inventory.

### 3. Automatic Replanting
When a roamer chops down a tree:
- They prioritize picking up dropped saplings
- They replant a sapling at the stump location
- If the sapling is unreachable (stuck in a hole, too far), they give up quickly and continue normal behavior - no freezing or idling

### 4. Pity System
If a roamer is idle for 2 minutes because they need an item they can't obtain or craft:
- The system automatically grants them 16 of the needed item
- Works for ANY item (logs, stone, sand, etc.)
- Efficiently implemented with no memory leaks
- Always enabled, no configuration needed

## Requirements

- Minecraft 1.21.1
- NeoForge 21.0+
- Roamers mod 2.0+

## Installation

1. Install NeoForge for Minecraft 1.21.1
2. Install the Roamers mod
3. Place this mod's JAR in your `mods` folder

## Building from Source

**Note:** If `gradle/wrapper/gradle-wrapper.jar` is missing, you can generate it by running:
```bash
gradle wrapper --gradle-version 8.9
```

Then build with:
```bash
./gradlew build
```

The built JAR will be in `build/libs/`

## For GitHub Actions

This project is set up to work with standard NeoForge GitHub Actions workflows. The build should work with a workflow like:

```yaml
name: Build

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3
      - name: Build with Gradle
        run: ./gradlew build
      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: RoamersPlus
          path: build/libs/*.jar
```

## License

MIT License
