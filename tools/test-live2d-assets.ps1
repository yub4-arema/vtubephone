$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$android = Join-Path $repo 'android'
$generated = Join-Path $android 'app/build/generated/live2d/assets'
$cache = Join-Path $android 'app/build/live2d-cache/live2dcubismcore.min.js'
$backup = "$cache.failure-test-backup"
$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ("vtubephone-live2d-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $fixture | Out-Null
function Build([string[]] $arguments) {
    & (Join-Path $android 'gradlew.bat') -p $android @arguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $arguments" }
}
function Manifest { Get-Content -Raw -LiteralPath (Join-Path $generated 'web/live2d-assets.json') | ConvertFrom-Json }
try {
    # No change to local.properties or owner files. -P overrides are test-only.
    Build @(':app:prepareLive2dAssets', "-Pvtubephone.modelDir=$fixture/missing")
    if ((Manifest).model -or (Manifest).modelError -ne 'モデル未設定') { throw 'Missing model did not fail gracefully' }
    Write-Output 'PASS: missing model -> status manifest'

    '{"Version":3,"FileReferences":{"Moc":"fixture.moc3","Textures":["broken.png"]}}' | Set-Content -Encoding utf8 (Join-Path $fixture 'fixture.model3.json')
    'fixture, not a real model' | Set-Content (Join-Path $fixture 'fixture.moc3')
    'invalid PNG' | Set-Content (Join-Path $fixture 'broken.png')
    Build @(':app:prepareLive2dAssets', "-Pvtubephone.modelDir=$fixture")
    if ((Manifest).model -or !(Manifest).modelError -or (Test-Path -LiteralPath (Join-Path $generated 'models/default'))) {
        throw 'Broken texture left a partial model'
    }
    Write-Output 'PASS: broken texture -> status manifest, partial model removed'

    if (Test-Path -LiteralPath $backup) { throw 'Backup already exists; refusing to overwrite' }
    if (Test-Path -LiteralPath $cache) { Move-Item -LiteralPath $cache -Destination $backup }
    try {
        Build @(':app:prepareLive2dAssets', '--offline', "-Pvtubephone.modelDir=$fixture/missing")
        if ((Manifest).core -or !(Manifest).coreError) { throw 'Core fetch failure did not fail gracefully' }
        Write-Output 'PASS: Core unavailable offline -> status manifest'
    } finally {
        if (Test-Path -LiteralPath $backup) { Move-Item -LiteralPath $backup -Destination $cache }
    }
} finally {
    # The only recursive cleanup target is our explicitly verified temp fixture.
    $resolvedFixture = [IO.Path]::GetFullPath($fixture)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolvedFixture.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
        ([IO.Path]::GetFileName($resolvedFixture)).StartsWith('vtubephone-live2d-')) {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
    } else { throw 'Unexpected fixture path' }
    Build @(':app:assembleDebug')
}
