# RTFStreamsConnector

A connector/companion mod that allows Streams Reflowing to understand the flow of ReTerraForged/NeoTerraForged rivers

## Important note: this mod DOESN'T WORK with the FreeTerraForged mod! The river carving was significally changed between the two.

## How it works
### 1. Flow direction calculation
The mod interfaces directly with the ReTerraForged (RTF) data structures (`Rivermap`, `Network`, `RiverWarp` etc.) and calculates a warped position of the queried block.
Then it uses finite differences across neighboring blocks to calculate the gradient of the lateral distance to the river's axis (the same lateral distance that is directly converted to a depth value in the river carver)
to figure out the local normal direction to the river's centerline, which is then rotated 90 degrees to get the downstream direction.

If a block is covered by multiple rivers (near river forks), the same computation is done for all covering rivers
and the resulting flow is a distance-to-centerline-weighted sum of the flow vectors for individual rivers

### 2. Streams Reflowing interface
The mod touches Streams with only two mixins:
* [RiverFlowFieldsMixin.java](src/main/java/rtfstreamsconnector/mixin/RiverFlowFieldsMixin.java) - overrides the `RiverFlowFields::flowRadAt()` method used by Streams' code to emit the flow direction for vanilla rivers
and intercepts `RiverFlowFields::classifierFor()` method to map the biome classifier to its `ServerLevel`
* [StreamFlowGridMixin.java](src/main/java/rtfstreamsconnector/mixin/StreamFlowGridMixin.java) - a dirty solution to a problem of Streams' bank boundary layer enclosing river mouths and pointing all of the flow towards a single point.
Uses multiple injection points in the `StreamFlowGrid::build()` to disable the boundary layer for OCEAN biome blocks and overrides their direction vector to point at 45 degrees away from the boundary

### 3. River lookup speed optimization
Since the approach of overriding the `flowRadAt()` method requires all of the math processing being done for each block, the most costly calculations:
* warping the block position for all rivers in a region
(there are around 1-4x the selected main river count rivers in a single RTF region and each tributary requires not only its own warp, but a warp chain of all its ancestors) to figure out which rivers cover the block,
* repeated recalculation of the lateral distance for the gradient computation
(the blocks are queried by Streams in a grid, so finite difference probes use distances to some already visited blocks);

can be cached. Here is how it is done:

#### [ChunkRiverCache.java](src/main/java/rtfstreamsconnector/rivers/ChunkRiverCache.java)
Costly river selection is amortized by per-block checking of only the rivers that cover at least one block in a given chunk.
The candidate list building still queries all the rivers in a region, but it is done lazily, one-time per chunk, when a block is queried from a chunk that hasn't been processed yet.

For each chunk to accurately get all candidate rivers it needs 4 probes *(each probe requires folding the block through the warp chain, calculating the distance and figuring out if the block is in the river channel or outside of it)* -
one at this chunk's North-West corner (the block with coordinates `[chunkX*4, chunkZ*4]`) and the same block for three neighboring chunks. Since these probes are also shared by neighboring chunks, they have their own cache layer.

#### [RiverDistanceCache.java](src/main/java/rtfstreamsconnector/rivers/RiverDistanceCache.java)

Computing finite difference gradient for each block requires the same process for the lateral distance calculation, and this distance is also reused between neighboring blocks. The cache stores the distance to the closest candidate river
for each block, which amortizes most of the distance calculations.

All caches are LRU with hard-coded limits. They shrink the processing time enough to make flow processing be as fast, or in some cases, faster than a player loading already generated terrain

## Known issues
* The river-ocean boundary flow isn't perfect since there are blocks that have biome:RIVER, but are not considered a part of an RTF river by the flow gating math.
* The river-ocean boundary fix uses the normal-to-river-boundary vector instead of the downstream direction, which makes the flow wiggle on warped river mouths
