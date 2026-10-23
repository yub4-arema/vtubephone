param(
    [string]$UnityEditorData = 'C:/Program Files/Unity/Hub/Editor/6000.0.44f1/Editor/Data',
    [string]$AndroidJar,
    [string]$Javac = 'javac'
)
$ErrorActionPreference = 'Stop'
$repoDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $repoDir
try {
    dotnet test tools/CoreTests/CoreTests.csproj --logger 'trx;LogFileName=core-tests.trx' --results-directory artifacts
    if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }

    # Use assemblies shipped with this Editor; this checks C# APIs, not Unity import or execution.
    $managedDir = Join-Path $UnityEditorData 'Managed'
    $cacheDir = Join-Path $UnityEditorData 'Resources/PackageManager/ProjectTemplates/libcache'
    $uiAssembly = Get-ChildItem -LiteralPath $cacheDir -Recurse -Filter 'UnityEngine.UI.dll' | Select-Object -First 1
    $inputAssembly = Get-ChildItem -LiteralPath $cacheDir -Recurse -Filter 'Unity.InputSystem.dll' | Select-Object -First 1
    if (!$uiAssembly -or !$inputAssembly) { throw 'Template cache UI/InputSystem assemblies not found' }
    $references = @((Join-Path $managedDir 'UnityEngine.dll'), (Join-Path $managedDir 'UnityEditor.dll'))
    $references += Get-ChildItem -LiteralPath (Join-Path $managedDir 'UnityEngine') -Filter '*.dll' | ForEach-Object FullName
    $references += $uiAssembly.FullName, $inputAssembly.FullName
    $referenceXml = foreach ($referencePath in $references) {
        $escapedPath = [System.Security.SecurityElement]::Escape($referencePath)
        $assemblyName = [System.Security.SecurityElement]::Escape([IO.Path]::GetFileNameWithoutExtension($referencePath))
        "<Reference Include=`"$assemblyName`"><HintPath>$escapedPath</HintPath></Reference>"
    }
    $project = @"
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup><TargetFramework>netstandard2.1</TargetFramework><EnableDefaultCompileItems>false</EnableDefaultCompileItems><DefineConstants>UNITY_EDITOR;UNITY_ANDROID</DefineConstants></PropertyGroup>
  <ItemGroup>
    $($referenceXml -join "`n")
    <Compile Include="../../app/Assets/VtubePhone/Core/*.cs" />
    <Compile Include="../../app/Assets/VtubePhone/Runtime/*.cs" />
    <Compile Include="../../app/Assets/VtubePhone/Editor/*.cs" />
  </ItemGroup>
</Project>
"@
    New-Item -ItemType Directory -Force artifacts/compile | Out-Null
    Set-Content -LiteralPath artifacts/compile/Compile.csproj -Value $project -Encoding utf8
    dotnet build artifacts/compile/Compile.csproj --nologo -v minimal
    if ($LASTEXITCODE -ne 0) { throw 'C# static compilation failed' }

    if ($AndroidJar) {
        & $Javac --release 8 '-Xlint:all,-options' -cp $AndroidJar -d artifacts/java app/Assets/Plugins/Android/HapticEngine.java app/Assets/Plugins/Android/MotionSensor.java
        if ($LASTEXITCODE -ne 0) { throw 'Android Java compilation failed' }
    }
}
finally { Pop-Location }
