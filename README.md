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
### Current bugs tracked:
- INTERACTION:
    - Players can place world blocks that would intersect with a grid
    - Players can place blocks on grids even if sight is blocked by a world block
    - Players can place grid blocks that would intersect with the world
    - Inconsistent grid block placement 
    - Players can place blocks on grids regardless of distance from grid
- ENTITY COLLISION:
    - Redo the entity collision system from the ground up
    - Entities tracked will never be untracked even when leaving active subchunks
    - MAJOR: Crash occurs; BoxShape tries to cast to CompoundShape
    - Entity collision bugs out when colliding with multiple collision shapes
- RENDER:
    - Grids renders jump when a new block is placed
- PHYSICS:
    - Physics objects will trip over mesh triangle edges when moving parallel to them, causing visual oddity
### Planned features:
- INTERACTION:
    - Block breaking
    - Interaction with block entities
- PHYSICS:
    - Different blocks having different mass.
    - Implement custom collision resolution system?
    - Buoyancy
    - Accurate subchunk mesh generation
    - Simpler grid block CompoundShape generation
- ENTITY COLLISION:
    - Grid passengers
- RENDER:
    - Custom shader rendering? Or try to implement vanilla rendering on blocks based on location? (Look at VS2 for inspiration)