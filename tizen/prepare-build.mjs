import { copyFileSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const root = process.cwd();
const release = JSON.parse(readFileSync(resolve(root, "shared/release.json"), "utf8"));
const config = readFileSync(resolve(root, "tizen/config.xml"), "utf8");
if (!/^\d+\.\d+\.\d+$/.test(release.version)) throw new Error("Invalid shared MUGEN release version");
const packagedConfig = config.replace(/version="[^"]+" viewmodes=/, `version="${release.version}" viewmodes=`);
const buildDir = resolve(root, "tizen/build");
writeFileSync(resolve(buildDir, "config.xml"), packagedConfig);
copyFileSync(resolve(root, "tizen/icon.png"), resolve(buildDir, "icon.png"));
console.log(`Prepared MUGEN TV project ${release.version} at tizen/build (certificate signing still required).`);
