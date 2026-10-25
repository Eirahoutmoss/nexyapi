# NexYapı sürüm hazırlama (Windows PowerShell)
#
# Kullanım (proje kökünde):
#   .\gradlew.bat clean :core:test :app:testReleaseUnitTest :app:assembleRelease
#   powershell -ExecutionPolicy Bypass -File tools\release\prepare-release.ps1
#
# Çıktı: dist\NexYapi-<sürüm>.apk ve dist\NexYapi-<sürüm>.apk.sha256
# İkisini de GitHub'da v<sürüm> etiketli sürüme yükleyin. .sha256 dosyası olmadan
# uygulama içi güncelleme KURULMAZ (bilinçli güvenlik kuralı).

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$gradle = Get-Content (Join-Path $root "app\build.gradle.kts") -Raw
if ($gradle -notmatch 'versionName\s*=\s*"([^"]+)"') { throw "versionName bulunamadı." }
$version = $Matches[1]

$apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { throw "İmzalı release APK yok: $apk  (keystore.properties tanımlı mı?)" }

$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$name = "NexYapi-$version.apk"
$out = Join-Path $dist $name
Copy-Item $apk $out -Force

$hash = (Get-FileHash -Algorithm SHA256 $out).Hash.ToLower()
# sha256sum biçimi: "<özet>  <dosya adı>" — Unicode BOM olmadan yazılır.
[System.IO.File]::WriteAllText("$out.sha256", "$hash  $name`n", (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Hazır:"
Write-Host "  $out"
Write-Host "  $out.sha256"
Write-Host "GitHub sürüm etiketi: v$version"
