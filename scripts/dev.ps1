# Arranca en esta ventana la base de datos de desarrollo o el servidor de TiendaPOSS.
#   dev.ps1 -Que db       PostgreSQL embebido en el puerto 55434 (los datos se conservan entre reinicios)
#   dev.ps1 -Que backend  API en el puerto 8090 (compila primero si hay cambios)
param([ValidateSet('db', 'backend')][string]$Que)

$ErrorActionPreference = 'Stop'

# Claves locales (no se suben a git): tiendaposs\.env.local con líneas CLAVE=valor, p. ej. ANTHROPIC_API_KEY=...
$envFile = Join-Path $PSScriptRoot '..\.env.local'
if (Test-Path $envFile) {
  foreach ($line in Get-Content $envFile) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim('"')) }
  }
}
$backend = Join-Path $PSScriptRoot '..\backend' | Resolve-Path
Set-Location $backend
$mvn = Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.11-bin\*\apache-maven-3.9.11\bin\mvn.cmd" | Select-Object -First 1

# Si cambiaron las dependencias (pom.xml más nuevo que la lista de librerías), se vuelve a preparar.
$stale = (Test-Path 'target\cp.txt') -and ((Get-Item 'pom.xml').LastWriteTime -gt (Get-Item 'target\cp.txt').LastWriteTime)
if ($stale -or -not (Test-Path 'target\cp.txt') -or -not (Test-Path 'target\test-classes\com\tiendaposs\DevDbRunner.class')) {
  Write-Host 'Preparando el proyecto (puede tardar unos minutos)...'
  & $mvn.FullName -q test-compile dependency:build-classpath "-Dmdep.outputFile=target/cp.txt" "-Dmdep.includeScope=test"
} elseif ($Que -eq 'backend') {
  Write-Host 'Compilando cambios...'
  & $mvn.FullName -q -o compile
}

$cp = Get-Content 'target\cp.txt' -Raw
if ($Que -eq 'db') {
  & java -cp "target\test-classes;target\classes;$cp" com.tiendaposs.DevDbRunner
} else {
  & java -cp "target\classes;$cp" com.tiendaposs.TiendaPossApplication
}
