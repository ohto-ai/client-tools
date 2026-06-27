/**
 * Generate Client Tools mod icon — 256x256 PNG via SVG + Sharp.
 * Design: Bold geometric tool motif — hex nut + wrench elements in gold on dark bg.
 * Uses only simple SVG shapes for pixel-perfect rendering.
 */
const sharp = require("sharp");
const path = require("path");

const SIZE = 256;
const CX = SIZE / 2;
const CY = SIZE / 2;

// ── Colors ──────────────────────────────────────────
const BG = "#1a1c2e";
const BG_BORDER = "#2e3152";
const GEAR_RING = "#282b42";
const GOLD = "#e0a030";
const GOLD_LT = "#f2cc78";
const GOLD_DK = "#b07818";
const BLUE = "#5b8de8";

// ── Helper: SVG path for a C-shaped arc (like a wrench head) ──
function cArcPath(cx, cy, r, width, startDeg, endDeg) {
  // Draws a thick arc (donut segment). startDeg to endDeg going clockwise.
  const toRad = (d) => (d * Math.PI) / 180;

  const startRad = toRad(startDeg);
  const endRad = toRad(endDeg);
  const rOuter = r;
  const rInner = r - width;

  // Outer arc points
  const sxOuter = cx + rOuter * Math.cos(startRad);
  const syOuter = cy + rOuter * Math.sin(startRad);
  const exOuter = cx + rOuter * Math.cos(endRad);
  const eyOuter = cy + rOuter * Math.sin(endRad);

  // Inner arc points
  const sxInner = cx + rInner * Math.cos(startRad);
  const syInner = cy + rInner * Math.sin(startRad);
  const exInner = cx + rInner * Math.cos(endRad);
  const eyInner = cy + rInner * Math.sin(endRad);

  // Clockwise angular distance from start to end
  const cwDist = ((endDeg - startDeg) % 360 + 360) % 360;
  const largeArc = cwDist > 180 ? 1 : 0;

  return [
    `M ${sxOuter} ${syOuter}`,
    `A ${rOuter} ${rOuter} 0 ${largeArc} 1 ${exOuter} ${eyOuter}`,
    `L ${exInner} ${eyInner}`,
    `A ${rInner} ${rInner} 0 ${largeArc} 0 ${sxInner} ${syInner}`,
    `Z`,
  ].join(" ");
}

// ── Build SVG ────────────────────────────────────────
const WRENCH_ANGLE = -30;
const HL = 64;  // handle half-length
const HW = 13;   // handle half-width
const HEAD_R = 32;
const HEAD_W = 11;  // ring width
const JAW = 42;      // jaw opening in degrees

// Head center in local coords (before rotation)
const headLocalY = -HL;

// Jaw direction: to the RIGHT of the handle
// In local coords (handle vertical), right = angle 0 (3 o'clock in SVG)
// After rotating the whole group by WRENCH_ANGLE, the jaw correctly faces right-of-handle
const JAW_CENTER = 0;  // 3 o'clock (right side of handle)
const JAW_START = JAW_CENTER - JAW / 2;  // -21°
const JAW_END = JAW_CENTER + JAW / 2;    // +21°
// The C goes from JAW_END to JAW_START clockwise (the long way)
const C_START = JAW_END;     // 21°
const C_END = JAW_START + 360; // 339° (expressed as -21 + 360)

// Small accent gear
const SG_R = 16;
const SG_TEETH = 8;
const SG_Y = HL + 12;

// Generate gear teeth SVG
function gearTeeth(cx, cy, r, n, toothH, toothW, color) {
  let lines = "";
  for (let i = 0; i < n; i++) {
    const a = ((360 / n) * i - 90) * Math.PI / 180;
    const x1 = cx + r * Math.cos(a);
    const y1 = cy + r * Math.sin(a);
    const x2 = cx + (r + toothH) * Math.cos(a);
    const y2 = cy + (r + toothH) * Math.sin(a);
    lines += `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${color}" stroke-width="${toothW}" stroke-linecap="round"/>\n    `;
  }
  return lines;
}

const svg = `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${SIZE}" height="${SIZE}" viewBox="0 0 ${SIZE} ${SIZE}">

  <defs>
    <radialGradient id="bgGlow" cx="50%" cy="45%" r="40%">
      <stop offset="0%" stop-color="#2a2d48" stop-opacity="0.5"/>
      <stop offset="100%" stop-color="#1a1c2e" stop-opacity="0"/>
    </radialGradient>

    <!-- Inner shadow for the center bolt -->
    <radialGradient id="boltGrad" cx="40%" cy="35%" r="50%">
      <stop offset="0%" stop-color="#2a2d48" stop-opacity="1"/>
      <stop offset="100%" stop-color="#151620" stop-opacity="1"/>
    </radialGradient>
  </defs>

  <!-- Background -->
  <rect x="0" y="0" width="${SIZE}" height="${SIZE}" rx="48" ry="48" fill="${BG}"/>
  <rect x="2" y="2" width="${SIZE - 4}" height="${SIZE - 4}" rx="46" ry="46"
        fill="none" stroke="${BG_BORDER}" stroke-width="2"/>
  <rect x="0" y="0" width="${SIZE}" height="${SIZE}" rx="48" ry="48" fill="url(#bgGlow)"/>

  <!-- Background gear circles -->
  <g opacity="0.4">
    <circle cx="${CX}" cy="${CY}" r="96" fill="none" stroke="${GEAR_RING}" stroke-width="1.5"/>
    <circle cx="${CX}" cy="${CY}" r="87" fill="none" stroke="${GEAR_RING}" stroke-width="1"/>
    <circle cx="${CX}" cy="${CY}" r="77" fill="none" stroke="${GEAR_RING}" stroke-width="1"/>
    ${Array.from({ length: 24 }, (_, i) => {
      const a = ((360 / 24) * i - 90) * Math.PI / 180;
      return `<line x1="${CX + 79 * Math.cos(a)}" y1="${CY + 79 * Math.sin(a)}" x2="${CX + 86 * Math.cos(a)}" y2="${CY + 86 * Math.sin(a)}" stroke="${GEAR_RING}" stroke-width="2" stroke-linecap="round"/>`;
    }).join("\n    ")}
  </g>

  <!-- Temporary marker: radial glow rings -->
  <g opacity="0.25">
    <circle cx="${CX}" cy="${CY - 6}" r="60" fill="none" stroke="${GOLD}" stroke-width="0.8"/>
    <circle cx="${CX}" cy="${CY - 6}" r="74" fill="none" stroke="${GOLD}" stroke-width="0.8"/>
    <circle cx="${CX}" cy="${CY - 6}" r="88" fill="none" stroke="${GOLD}" stroke-width="0.6"/>
  </g>

  <!-- ═══ WRENCH ═══ -->
  <g transform="rotate(${WRENCH_ANGLE}, ${CX}, ${CY})">

    <!-- Handle -->
    <rect x="${CX - HW}" y="${CY - HL}" width="${HW * 2}" height="${HL * 2}"
          rx="4" fill="${GOLD}"/>

    <!-- Handle highlight (left) -->
    <rect x="${CX - HW}" y="${CY - HL + 12}" width="4" height="${HL * 2 - 24}"
          rx="1" fill="${GOLD_LT}" opacity="0.55"/>

    <!-- Handle shadow (right) -->
    <rect x="${CX + HW - 4}" y="${CY - HL + 12}" width="4" height="${HL * 2 - 24}"
          rx="1" fill="${GOLD_DK}" opacity="0.5"/>

    <!-- Grip lines -->
    ${Array.from({ length: 5 }, (_, i) => {
      const gy = CY - HL + 24 + i * 21;
      return `
        <line x1="${CX - HW + 3}" y1="${gy - 2}" x2="${CX + HW - 3}" y2="${gy - 2}"
              stroke="${GOLD_DK}" stroke-width="2" stroke-linecap="round" opacity="0.5"/>
        <line x1="${CX - HW + 3}" y1="${gy + 2}" x2="${CX + HW - 3}" y2="${gy + 2}"
              stroke="${GOLD_LT}" stroke-width="1.5" stroke-linecap="round" opacity="0.4"/>`;
    }).join("\n    ")}

    <!-- Wrench head — C-shaped arc -->
    <!-- Outer ring C -->
    <path d="${cArcPath(CX, CY - HL, HEAD_R, HEAD_W, C_START, C_END)}" fill="${GOLD}"/>

    <!-- Jaw tip highlights (small circles at the C ends) -->
    ${(() => {
      const toRad = (d) => (d * Math.PI) / 180;
      let svgOut = "";
      for (const tipDeg of [JAW_START, JAW_END]) {
        const tr = toRad(tipDeg);
        const tx = CX + HEAD_R * Math.cos(tr);
        const ty = (CY - HL) + HEAD_R * Math.sin(tr);
        svgOut += `<circle cx="${tx}" cy="${ty}" r="5.5" fill="${GOLD_LT}"/>\n        `;
      }
      return svgOut;
    })()}

    <!-- Head center bolt -->
    <circle cx="${CX}" cy="${CY - HL}" r="${HEAD_R - HEAD_W - 1}"
            fill="url(#boltGrad)" stroke="${GOLD_DK}" stroke-width="1.5" opacity="0.8"/>

    <!-- Small accent gear at handle bottom -->
    ${(() => {
      const sgy = CY + SG_Y;
      let svgOut = `<circle cx="${CX}" cy="${sgy}" r="${SG_R}" fill="none" stroke="${GOLD}" stroke-width="4"/>`;
      for (let i = 0; i < SG_TEETH; i++) {
        const a = ((360 / SG_TEETH) * i - 90) * Math.PI / 180;
        const x1 = CX + SG_R * Math.cos(a);
        const y1 = sgy + SG_R * Math.sin(a);
        const x2 = CX + (SG_R + 7) * Math.cos(a);
        const y2 = sgy + (SG_R + 7) * Math.sin(a);
        svgOut += `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${GOLD}" stroke-width="3.5" stroke-linecap="round"/>`;
      }
      svgOut += `<circle cx="${CX}" cy="${sgy}" r="7" fill="${BG}" stroke="${GOLD}" stroke-width="2.5"/>`;
      return svgOut;
    })()}

  </g>

  <!-- Corner accents -->
  <g opacity="0.6">
    ${[
      [32, 32, 32, 58],
      [224, 32, 224, 58],
      [32, 224, 32, 198],
      [224, 224, 224, 198],
    ].map(([cx, cy, lx, ly]) => `
        <circle cx="${cx}" cy="${cy}" r="3" fill="${BLUE}"/>
        <line x1="${cx}" y1="${cy}" x2="${lx}" y2="${ly}" stroke="${BLUE}" stroke-width="1" opacity="0.35"/>`
    ).join("\n    ")}
  </g>

</svg>`;

// ── Render ───────────────────────────────────────────
const outputPath = path.join(
  __dirname,
  "src", "client", "resources", "assets", "client-tools",
  "icon.png"
);

async function main() {
  try {
    const svgBuffer = Buffer.from(svg);
    await sharp(svgBuffer)
      .resize(SIZE, SIZE)
      .png()
      .toFile(outputPath);
    console.log("Icon saved:", outputPath);
    console.log("Size: 256x256");
  } catch (err) {
    console.error("Error:", err.message);
    // Print the SVG for debugging
    console.log("\n--- SVG preview (first 500 chars) ---");
    console.log(svg.substring(0, 500));
  }
}

main();
