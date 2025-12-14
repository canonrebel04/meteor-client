# Meteor Client with Enhanced Baritone Integration

A feature-rich fork of [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) with deep Baritone integration, advanced automation, and intelligent navigation systems.

## 🌟 Key Features

### 🤖 Advanced Automation
- **TreeFarm**: Automated tree farming using Baritone mining + auto-replanting
- **AutoFarm**: Selective crop farming with configurable crop types
- **AutoReturn**: Automatically returns to home when Baritone pauses
- **CombatMovement**: AI-driven combat positioning using Utility AI scoring

### 🗺️ Enhanced Navigation
- **Route System**: Create multi-waypoint routes with ETA and cost estimates
- **Avoidance Zones**: Define no-go areas for Baritone pathfinding
- **Smart Goto**: Enhanced pathfinding with safety checks and recovery
- **Stuck Detection**: Automatic detection and recovery from stuck states

### 📦 Storage Management
- **StorageManager**: Passive indexing of container contents
- **`.storage find <item>`**: Locate items across all indexed chests
- **`.storage debug`**: View storage statistics

### 🎮 Baritone Enhancements
- **Baritone Settings UI**: In-game GUI for all Baritone settings
- **Task Actions**: Quick access to common Baritone tasks (mine, explore, farm)
- **Path Visualization**: Real-time path rendering with cost and ETA display
- **Recovery Mode**: Safe fallback when Baritone encounters issues

### 🛡️ Safety Features
- **Threat Manager**: Track and score nearby threats
- **Combat-Aware Pathing**: Avoid dangerous areas during navigation
- **Safe Mode**: Conservative settings for high-risk scenarios
- **Damage Prediction**: Estimate path danger before committing

## 🚀 Installation

### Prerequisites
- Minecraft 1.21.10
- Fabric Loader
- Fabric API

### Build from Source

1. **Clone the repository**:
   ```bash
   git clone https://github.com/canonrebel04/meteor-client.git
   cd meteor-client
   ```

2. **Build**:
   ```bash
   ./gradlew build
   ```

3. **Deploy to PrismLauncher** (optional):
   ```bash
   ./deploy.sh
   ```

   The jar will be in `build/libs/meteor-client-1.21.10-local.jar`

### Dependencies
This fork includes a bundled version of Baritone. No separate Baritone installation required.

## 📖 Usage

### TreeFarm Module
```
1. Enable TreeFarm module (Category: Movement)
2. Configure which log types to mine (default: all)
3. Ensure saplings are in hotbar
4. Stand near trees - Baritone will mine and replant automatically
```

### AutoFarm Module
```
1. Enable AutoFarm (Category: Movement)
2. Select preferred crops (Wheat, Carrots, Potatoes, etc.)
3. Stand in or near farm - bot will harvest ripe crops and replant
```

### Route System
```
.route create <name>          - Create new route
.route add <waypoint>          - Add waypoint to route
.route start <name>            - Begin navigating route
.route list                    - List all routes
```

### Storage Commands
```
.storage find <item>           - Find item in indexed chests
.storage debug                 - View storage statistics
```

### Avoidance Zones
```
.avoidance add <name> <radius> - Create avoidance zone at current position
.avoidance list                - List all zones
.avoidance remove <name>       - Remove zone
```

## 🔧 Notable Changes from Upstream

- **Baritone Integration**: Native Baritone support with settings GUI
- **Custom Automation**: TreeFarm, AutoFarm, CombatMovement modules
- **Route System**: Multi-waypoint navigation with persistence
- **Storage Indexing**: Passive container scanning and search
- **Enhanced HUD**: Route display, Baritone path visualization
- **Safety Systems**: Threat detection, avoidance zones, stuck recovery

## 📝 Commands Reference

### Navigation
- `.home set` - Set home location
- `.home goto` - Path to home
- `.goto <x> <y> <z>` - Enhanced goto with safety checks
- `.route <create|add|start|list>` - Route management

### Automation
- `.farm start` - Start farming
- `.mine <block>` - Mine specific block type

### Storage
- `.storage find <item>` - Locate item
- `.storage debug` - Storage info

### Avoidance
- `.avoidance add <name> <radius>` - Add zone
- `.avoidance list` - List zones

## 🤝 Contributing

This is a personal fork focused on Baritone integration and automation. For general Meteor Client contributions, please see the [upstream repository](https://github.com/MeteorDevelopment/meteor-client).

## 📜 License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.

## 🙏 Credits

- **Meteor Client**: [MeteorDevelopment](https://github.com/MeteorDevelopment/meteor-client)
- **Baritone**: [cabaletta](https://github.com/cabaletta/baritone)
- Enhanced features and integration by [canonrebel04](https://github.com/canonrebel04)

## 📚 Documentation

For detailed information:
- [Baritone Behavior Guide](BARITONE_BEHAVIOR_GUIDE.md)
- [Automation Runtime](AUTOMATION_RUNTIME.md)
- [Baritone Testing](BARITONE_TESTING.md)

## ⚠️ Disclaimer

This is a utility mod intended for single-player or authorized server use. Always follow server rules and respect other players.
