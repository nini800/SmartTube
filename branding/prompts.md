# PinouTube generation record

Built-in ImageGen, 2026-10-25. References were inspected without modification: PinouPlex V2 icon/banner and v2-prompts.md/README.md; Pinou Shield brand preview; original PinouLogoBlackBG.jpg.

## Selected generation and final derivation

Generation 1 (banner) selected as the sole geometry source. Its exact wordmark is PinouTube. Generations 2–4 were inspected and discarded: separate extractions risked contour drift and transparent cutouts contained stray pixels. Final exports derive directly from generation 1, with deterministic palette normalization to #FF0033 / #F5F7F7, alpha extraction, crop and high-quality resizing using System.Drawing. No new shapes or lettering were drawn. All three share the identical source emblem. Opaque backgrounds are #0B0E10. Banner: 1536×864; icon: 1024×1024, emblem 74% height; transparent mark: 1024×1024, emblem 92% height. The original generated banner was 1672×941 and was resampled to exact 16:9. Only final deliverables retained.

## 1 — Banner (selected)

```text
Use case: logo-brand. Create the final Android TV launcher banner for "PinouTube", the owner's private YouTube client. Output 1536x864 (exact 16:9), one finished image.
References already inspected: PinouPlex V2 banner and icon demonstrate quality and bold integrated geometry; Pinou Shield Pro demonstrates a partner symbol woven continuously into the folded left ear; the original Pinou logo supplies asymmetric ears (left folded, right tall), not a tracing target.
Design ONE expertly fused red emblem: retain a clearly recognizable YouTube rounded rectangular red play-button body with solid WHITE right-pointing triangle, while its continuous contours flow naturally into Pinou's folded left ear and tall open right ear. The ears and button must share deliberate curves and connected red geometry, never look like ears pasted onto a rectangle. No rabbit face. The folded left ear and tall right ear must be distinct. Strong simple silhouette and substantial strokes that read at 50dp. Use flat YouTube red #FF0033 and white #F5F7F7. No enclosing circle.
Composition: emblem left, exact wordmark "PinouTube" right, centered balanced horizontal lockup, occupying central 75% width and approximately 44% height, generous negative space. Wordmark in clean bold custom-looking geometric sans, white, readable television typography. Text precisely P-i-n-o-u-T-u-b-e, case-sensitive, no spaces. ONLY text: PinouTube.
Absolutely uniform flat near-black #0B0E10 background. Flat vector-like pristine artwork. No gradients, lighting, texture, grain, glow, 3D, shadows, borders, mockups, extra symbols or slogan. Match the sophistication of PinouPlex V2 while making YouTube's red button and white triangle immediately recognizable.
```

## 2 — Icon extraction (discarded)

```text
Use case: precise-object-edit / logo-brand. Derive the square PinouTube app icon from the selected banner input. Preserve EXACTLY the emblem on the LEFT of that banner: same continuous red folded left ear, tall open right ear, red YouTube rounded-rectangle body, and WHITE play triangle. Preserve every contour, angle, cutout, proportion, and color. This is extraction and reframing, NOT a redesign. Remove the entire wordmark. Center the same emblem in a 1024x1024 square, its tallest extent occupying 74% of canvas, aspect ratio preserved. Completely uniform flat near-black #0B0E10 background. Solid flat #FF0033 red and #F5F7F7 white, antialiased edges only; no texture, gradients, shadows, glow, borders or extra elements.
```

## 3 — Transparent mark extraction (discarded)

```text
Use case: background-extraction. Extract ONLY the red emblem at the LEFT of the supplied PinouTube banner. Preserve its exact geometry and colors: continuous red folded left ear, tall open right ear, rounded rectangular YouTube body, solid white right-facing play triangle. Do not redesign, redraw, change proportions or add details. Remove all lettering. Remove ALL near-black background including inside both ear openings. Actual transparent alpha background, no dark box, no checkerboard pixels, no halo. White triangle remains solid opaque white, red regions remain solid opaque red #FF0033. Center the identical emblem on a square 1024x1024 transparent canvas, tightly framed with about 4% margin top and bottom, aspect ratio preserved. Flat vector-like colors, clean antialiased edges, no gradients, shadows, glow or texture. This is the matching top-right badge for a black TV UI, must read clearly at 50dp.
```

## 4 — Transparent mark cleanup (discarded)

```text
Use case: background-extraction. Clean the supplied transparent PinouTube badge. Keep precisely the same red silhouette and white play triangle, unchanged geometry. Remove all scattered stray pixels in the empty background and inside both ear holes. All empty areas must have alpha exactly zero. Make the interiors flat solid #FF0033 red and #F5F7F7 white, with no texture, grain or variation. Keep smooth antialiased contours without dark fringes. Square transparent canvas with 4 percent margin around the full silhouette. No text or new shapes. Real transparent background.
```

