import { readFileSync } from "node:fs";

const src = readFileSync(
  "/e/Desktop/AI-storyboard/AIStoryboardClient/src/components/scene/SceneCard.tsx",
  "utf-8"
);

const checks = [];

// 1. useRef imported
checks.push({
  label: "useRef imported",
  pass: /import\s*\{\s*useState,\s*useRef\s*\}/.test(src),
});

// 2. sceneRefImages state declared
checks.push({
  label: "sceneRefImages useState<string[]> declared",
  pass: /useState<string\[\]>/.test(src) && /setSceneRefImages/.test(src),
});

// 3. refInputRef declared
checks.push({
  label: "refInputRef useRef<HTMLInputElement> declared",
  pass: /useRef<HTMLInputElement>/.test(src),
});

// 4. generateImage passes referenceImages (4th arg, non-empty guard)
checks.push({
  label: "handleGenerateImage passes sceneRefImages as 4th arg with length guard",
  pass: /generateImage\(scene\.id,\s*imagePrompt,\s*imageModel,\s*sceneRefImages\.length\s*>\s*0\s*\?\s*sceneRefImages\s*:\s*undefined\)/.test(src),
});

// 5. generateVideo passes referenceImages (4th arg, non-empty guard)
checks.push({
  label: "handleGenerateVideo passes sceneRefImages as 4th arg with length guard",
  pass: /generateVideo\(scene\.id,\s*videoPrompt,\s*videoModel,\s*sceneRefImages\.length\s*>\s*0\s*\?\s*sceneRefImages\s*:\s*undefined\)/.test(src),
});

// 6. Hidden file input with ref
checks.push({
  label: "hidden file input bound to refInputRef",
  pass: /ref=\{refInputRef\}\s*type="file"/.test(src),
});

// 7. Upload button triggers ref click
checks.push({
  label: "upload button triggers refInputRef.current?.click()",
  pass: /refInputRef\.current\?\.click\(\)/.test(src),
});

// 8. Max 3 images guard
checks.push({
  label: "max 3 images alert guard present",
  pass: /alert\('最多3张参考图'\)/.test(src),
});

// 9. Thumbnail removal with delete button
checks.push({
  label: "thumbnail × delete button present",
  pass: /setSceneRefImages\(prev\s*=>\s*prev\.filter\(\(_,j\)\s*=>\s*j!==i\)\)/.test(src),
});

// 10. store generateImage signature has referenceImages param
const storeSrc = readFileSync(
  "/e/Desktop/AI-storyboard/AIStoryboardClient/src/stores/projectStore.ts",
  "utf-8"
);
checks.push({
  label: "projectStore generateImage accepts referenceImages?: string[]",
  pass: /generateImage:\s*\(sceneId,\s*prompt,\s*model\?,\s*referenceImages\?\)/.test(storeSrc),
});
checks.push({
  label: "projectStore generateVideo accepts referenceImages?: string[]",
  pass: /generateVideo:\s*\(sceneId,\s*prompt,\s*model\?,\s*referenceImages\?\)/.test(storeSrc),
});

// Summary
let allPass = true;
for (const c of checks) {
  console.log(`  ${c.pass ? "✓" : "✗"} ${c.label}`);
  if (!c.pass) allPass = false;
}
console.log(`\n${allPass ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED"}`);
process.exit(allPass ? 0 : 1);
