# Stardance
Physics engine library mod for Fabric 1.20.1
A Minecraft port of the Bullet Physics engine library (thanks to jBullet)
Mod is very much in development stages. More bugs than a rain forest.
Mostly just a library mod. Creative mode tools exist to experiment and debug.
### API
Physics objects are structured in "LocalGrids", made of vanilla (or modded) Minecraft blocks. Grid's blocks can be interacted with just like standard vanilla blocks (eventually...). Information on the API can be found in the LocalGrid class.
### Features
- Physics objects (LocalGrids) with collision and basic physics.
- Rendering system to render both grid objects and a debug renderer for all collision shapes.
- Basic player interaction (buggy entity collision, blocks can be placed on grids)
- Debug tools found in creative menu
### To be implemented:
- A lot