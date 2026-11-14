Status: OPEN

# Engineering tasks (3D case, hardware)

Done by the `3d-engineer` skill. Files: `~/projects/pessoal/bomba/Med-Link-v4/Firmware/medlink_v4_.stl/`.

## E1 — Pin: the hole for the switch is too small
Priority: P2
Seen: user, 2026-11-14: "the problem with the pin is that the hole to put it in the switch is too small".
What the pin is (measured from `medlink_v4_pin_v402.stl`, mm):
- A flat base 6 x 14 x 1 (rounded ends; X -1.5..4.5, Y -3..11, Z 0..1).
- A block on top, 3 x 3 x 2 (X 0..3, Y 2.5..5.5, Z 1..3).
- **The switch hole**: a square 1.5 x 1.5 (X 0.75..2.25, Y 3.25..4.75) from Z 1 to Z 3, so 2 mm deep, open at
  the top. The block walls around it are 0.75 mm thick.
- The switch on the board: **SK12N14F02-2510**, a 2-position right-angle slide switch (from
  `Zestawienie materiałów z wyceną i linkami do sklepów.pdf`, line "przełącznik"). The pin goes on its slide
  lever (actuator).
What happens: the hole is too small, so the pin does not go onto the switch lever.
What should happen: the pin goes onto the lever by hand with a light press, holds without glue, and moves the
switch between both positions.
Steps:
1. **Get the real lever size.** Ask the user to measure it with a caliper (width x thickness x free height).
   Also check the datasheet of SK12N14F02-2510 for the lever size. Write both here. Do not guess a size.
2. New hole = the lever size + **0.2 mm of play on each side** (+0.4 mm in total), as a start. Ask the user
   if they want a tighter fit (0.1 mm). Keep the hole depth at or a bit less than the free lever height.
3. Keep the block walls at least **0.8 mm** (2 lines with a 0.4 mm nozzle). If the bigger hole makes them
   thinner, make the block bigger and keep it centered on the hole. Check that the bigger block still fits the
   switch opening in the box (`medlink_v4_box_v403_usb_c.stl`): find that opening with `section` and measure it.
4. Fix the mesh at the same time. Today it has 12 edges shared by more than 2 facets, facets on 16 edges that
   point the wrong way, and 1 flat facet. Also, the solid name inside says `v300`.
5. The cut needs a boolean tool (OpenSCAD or trimesh + manifold3d). Ask the user before installing one. With
   OpenSCAD, it is simpler to model the pin again from the sizes above (base + block − hole) than to cut the
   old mesh. That also gives a clean mesh and a `.scad` file the user can change later
   (`pin_v403.scad`, kept in the work folder).
6. Make 2 or 3 versions with different play (for example +0.3, +0.4 and +0.5 mm in total), so the user can
   print them all at once and keep the best one.
Checks needed: `check` with no problems; `info` for the sizes; `section` at Z 2 before and after, with the
hole size written on it; `compare` with v402 (only the hole and the block changed).
Field test: the user prints the versions (the base flat on the bed, no support needed) and tries each one on the
switch. The best one goes on by hand, holds, and moves the switch both ways. The user tells the engineer which
one; that one becomes `medlink_v4_pin_v403.stl`.
Output: `medlink_v4_pin_v403.stl` (a new file; never write over v402), plus the `.scad` source.
