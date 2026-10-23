$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$framework = 'artifacts/t03/framework'
if (!(Test-Path -LiteralPath $framework)) {
    git clone --branch 5-r.4 --depth 1 https://github.com/Live2D/CubismWebFramework.git $framework
    if ($LASTEXITCODE -ne 0) { throw 'Framework clone failed' }
}
if ((git -C $framework rev-parse HEAD) -ne '8df84780f2aa1298f3b30965cdae143e049f3c8e') { throw 'Unexpected Framework revision' }
$destination = 'android/app/src/main/assets/web/vendor/cubism'
New-Item -ItemType Directory -Force $destination | Out-Null
npx --yes esbuild@0.25.5 tools/live2d-framework-entry.js --bundle --format=esm --target=es2020 --legal-comments=inline --outfile=android/app/src/main/assets/web/vendor/cubism/framework.js
if ($LASTEXITCODE -ne 0) { throw 'Framework bundle failed' }
Copy-Item -LiteralPath "$framework/LICENSE.md" -Destination "$destination/LICENSE.md"
